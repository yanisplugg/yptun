package coreapi

import (
	"encoding/json"
	"fmt"
)

type appleFreeTurnOptions struct {
	URI        string `json:"uri"`
	ListenAddr string `json:"listen_addr"`
	VKLink     string `json:"vk_link"`
	Streams    int    `json:"streams"`
}

type appleMasterDNSOptions struct {
	WorkDir             string `json:"work_dir"`
	Domains             string `json:"domains"`
	Key                 string `json:"key"`
	EncryptionMethod    int    `json:"encryption_method"`
	Resolvers           string `json:"resolvers"`
	ListenAddr          string `json:"listen_addr"`
	BalancingStrategy   int    `json:"balancing_strategy"`
	PacketDuplication   int    `json:"packet_duplication"`
	UploadCompression   int    `json:"upload_compression"`
	DownloadCompression int    `json:"download_compression"`
}

type appleOpenFluxOptions struct {
	Transport        string `json:"transport"`
	URL              string `json:"url"`
	Secret           string `json:"secret"`
	MaxToken         string `json:"max_token"`
	MaxUID           string `json:"max_uid"`
	AvitoCookies     string `json:"avito_cookies"`
	SessionSpecsJSON string `json:"session_specs_json"`
	ListenAddr       string `json:"listen_addr"`
	Debug            bool   `json:"debug"`
}

func decodeAppleOptions(text string, target any) error {
	if err := json.Unmarshal([]byte(text), target); err != nil {
		return fmt.Errorf("decode Apple engine options: %w", err)
	}
	return nil
}

// AppleEngineStart is the flat gomobile entry point for Go sidecar runtimes whose native argument
// shapes differ. Rust emits optionsJSON; Swift never mirrors the persisted location models.
func AppleEngineStart(kind, optionsJSON string) error {
	switch kind {
	case "freeturn":
		var o appleFreeTurnOptions
		if err := decodeAppleOptions(optionsJSON, &o); err != nil {
			return err
		}
		return FtStart(o.URI, o.ListenAddr, o.VKLink, o.Streams)
	case "wdtt":
		var o struct {
			OptionsJSON string `json:"options_json"`
		}
		if err := decodeAppleOptions(optionsJSON, &o); err != nil {
			return err
		}
		return WdttStart(o.OptionsJSON)
	case "masterdns":
		var o appleMasterDNSOptions
		if err := decodeAppleOptions(optionsJSON, &o); err != nil {
			return err
		}
		return MasterDnsStart(
			o.WorkDir, o.Domains, o.Key, o.EncryptionMethod, o.Resolvers,
			o.ListenAddr, "", "", o.BalancingStrategy, o.PacketDuplication,
			o.UploadCompression, o.DownloadCompression,
		)
	case "openflux":
		var o appleOpenFluxOptions
		if err := decodeAppleOptions(optionsJSON, &o); err != nil {
			return err
		}
		return OpenFluxStart(
			o.Transport, o.URL, o.Secret, o.MaxToken, o.MaxUID, o.AvitoCookies,
			o.SessionSpecsJSON, o.ListenAddr, "", "", o.Debug,
		)
	default:
		return fmt.Errorf("unsupported Apple engine kind %q", kind)
	}
}

func AppleEngineStopAll() {
	FtStop()
	WdttStop()
	MasterDnsStop()
	OpenFluxStop()
}
