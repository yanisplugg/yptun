package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/samosvalishe/free-turn-proxy/internal/clientid"
	"github.com/samosvalishe/free-turn-proxy/internal/config"
	"github.com/samosvalishe/free-turn-proxy/internal/logx"
	"github.com/samosvalishe/free-turn-proxy/internal/provider/vk"
	"github.com/samosvalishe/free-turn-proxy/internal/proxy/udprelay"
	"github.com/samosvalishe/free-turn-proxy/internal/session"
	"github.com/samosvalishe/free-turn-proxy/internal/sub"
	"github.com/samosvalishe/free-turn-proxy/internal/wire/rtpopus"
)

// version is populated at build time via -ldflags "-X main.version=...".
var version = "dev"

func main() {
	args := os.Args[1:]

	// -sub: тянем список серверов до парсинга и подсовываем URI первой ноды
	// (Nodes[0], без failover) позиционным freeturn:// - ParseClient применит его
	// тем же путём, что и URI из CLI. Подписка должна стоять до парсинга: она даёт
	// peer, без которого ParseClient падает на валидации.
	if subURL := config.PeekSubURL(args); subURL != "" {
		s, ferr := sub.Fetch(context.Background(), subURL)
		if ferr != nil {
			log.Fatalf("failed to fetch subscription: %v", ferr)
		}
		if len(s.Nodes) == 0 || s.Nodes[0].URI == nil {
			log.Fatalf("no nodes found in subscription")
		}
		args = append(args, s.Nodes[0].URI.String())
	}

	cfg, err := config.ParseClient(args, os.Stderr)
	if err != nil {
		// -help/-h: usage уже напечатан в ParseClient, выходим штатно.
		if errors.Is(err, flag.ErrHelp) {
			os.Exit(0)
		}
		// логгер ещё не создан - единственный fatal до его инициализации.
		log.Fatalf("%v", err)
	}

	// До резолва client ID: генерация ключа - чистая утилита, файлов после себя
	// оставлять не должна.
	if cfg.Obf.GenKey {
		key, gerr := rtpopus.GenKeyHex()
		if gerr != nil {
			log.Fatalf("gen-obf-key: %v", gerr)
		}
		fmt.Println(key)
		return
	}

	logger := logx.New(cfg.Log.Debug)
	logger.Infof("Free Turn Proxy client version=%s", version)

	idPaths := clientid.DefaultPaths()
	id, persisted, err := clientid.Resolve(cfg.ClientID, idPaths)
	if err != nil {
		logger.Errorf("%v", err)
		os.Exit(1)
	}
	if !persisted {
		logger.Warnf("client ID не сохранён ни по одному пути (%v) - будет новым при следующем запуске", idPaths)
	}
	cfg.ClientID = id
	logger.Infof("Client ID: %s", cfg.ClientID)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	signalChan := make(chan os.Signal, 1)
	signal.Notify(signalChan, syscall.SIGTERM, syscall.SIGINT)
	go func() {
		<-signalChan
		logger.Infof("Terminating...")
		cancel()
		select {
		case <-signalChan:
		case <-time.After(5 * time.Second):
		}
		logger.Errorf("Exit...")
		cancel()
		os.Exit(1)
	}()

	sess, err := session.New(cfg, session.Deps{
		Logger: logger,
		Solver: vk.DefaultManualSolver,
	})
	if err != nil {
		logger.Errorf("%v", err)
		os.Exit(1)
	}

	if err := sess.Run(ctx); err != nil {
		if errors.Is(err, udprelay.ErrFatal) {
			logger.Errorf("fatal: %v", err)
		} else {
			logger.Errorf("%v", err)
		}
		os.Exit(1)
	}
}
