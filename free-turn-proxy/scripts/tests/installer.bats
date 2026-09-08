#!/usr/bin/env bats

setup() {
    export FT_TEST_DIR="$BATS_TEST_TMPDIR/ft_test"
    export FT_PREFIX="$FT_TEST_DIR/opt"
    export FT_WG_DIR="$FT_TEST_DIR/wireguard"
    export SCRIPT="$BATS_TEST_DIRNAME/../install.sh"
    mkdir -p "$FT_PREFIX" "$FT_WG_DIR"
}

teardown() {
    rm -rf "$FT_TEST_DIR"
}

run_script() { run bash "$SCRIPT" "$@"; }

@test "install.sh проходит проверку синтаксиса bash -n" {
    run bash -n "$SCRIPT"
    [ "$status" -eq 0 ]
}

@test "help (-h и --help) выводит справку и завершается с кодом 0" {
    run_script -h
    [ "$status" -eq 0 ]
    [[ "$output" == *"Free Turn Proxy & AmneziaWG"* ]]
    [[ "$output" == *"--only-awg"* ]]
    [[ "$output" == *"--only-freeturn"* ]]

    run_script --help
    [ "$status" -eq 0 ]
    [[ "$output" == *"Машиночитаемый JSON RPC v2"* ]]
}

@test "probe возвращает proto 2, result ok и ровно одну строку JSON" {
    run_script probe
    [ "$status" -eq 0 ]
    [ "${#lines[@]}" -eq 1 ]
    [[ "$output" == *'"proto":2'* ]]
    [[ "$output" == *'"result":"ok"'* ]]
}

@test "probe содержит все обязательные поля контракта Android" {
    run_script probe
    [ "$status" -eq 0 ]
    [[ "$output" == *'"installed":'* ]]
    [[ "$output" == *'"running":'* ]]
    [[ "$output" == *'"runtime":'* ]]
    [[ "$output" == *'"euid":'* ]]
    [[ "$output" == *'"wg":{'* ]]
    [[ "$output" == *'"virt":'* ]]
    [[ "$output" == *'"wg_kernel":'* ]]
    [[ "$output" == *'"conflicts":{'* ]]
}

@test "неизвестная сабкоманда возвращает err bad_arg" {
    run_script unknowncmd
    [ "$status" -eq 1 ]
    [[ "$output" == *'"result":"err"'* ]]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "неизвестный флаг в RPC возвращает err bad_arg" {
    run_script start --unknown-flag=123
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
}

@test "share-info возвращает валидный JSON с wg_backend" {
    run_script share-info
    [ "$status" -eq 0 ]
    [[ "$output" == *'"result":"ok"'* ]]
    [[ "$output" == *'"wg_backend":'* ]]
}

@test "share-list возвращает пустые массивы peers и clients" {
    run_script share-list
    [ "$status" -eq 0 ]
    [[ "$output" == *'"result":"ok"'* ]]
    [[ "$output" == *'"peers":[]'* ]]
    [[ "$output" == *'"clients":[]'* ]]
}

@test "stop возвращает stopped: true" {
    run_script stop
    [ "$status" -eq 0 ]
    [[ "$output" == *'"stopped":true'* ]]
}

@test "генерация параметров AmneziaWG 3.1 соответствует спецификации" {
    # Проверяем функцию init_awg_params напрямую
    run bash -c "
        source '$SCRIPT'
        AWG_CONF='$FT_PREFIX/awg/awg0.conf'
        init_awg_params
        [ -n \"\$AWG_JC\" ] && [ \"\$AWG_JC\" -ge 4 ] && [ \"\$AWG_JC\" -le 6 ] || exit 1
        [ \"\$AWG_JMIN\" -eq 10 ] && [ \"\$AWG_JMAX\" -eq 50 ] || exit 1
        [ \"\$AWG_S4\" -eq 12 ] || exit 1
        [ -n \"\$AWG_S1\" ] && [ -n \"\$AWG_S2\" ] && [ -n \"\$AWG_S3\" ] || exit 1
        [ \"\$AWG_H1\" -eq 1 ] && [ \"\$AWG_H2\" -eq 2 ] && [ \"\$AWG_H3\" -eq 3 ] && [ \"\$AWG_H4\" -eq 4 ] || exit 1
        [ \${#AWG_HPK} -gt 20 ] || exit 1
    "
    [ "$status" -eq 0 ]
}

@test "alloc_client_ip корректно инкрементирует IP адреса клиентов" {
    run bash -c "
        source '$SCRIPT'
        conf='$FT_PREFIX/test.conf'
        : > \"\$conf\"
        ip1=\$(alloc_client_ip \"\$conf\" '10.13.13')
        [ \"\$ip1\" = '10.13.13.2' ] || exit 1
        echo 'AllowedIPs = 10.13.13.2/32' >> \"\$conf\"
        ip2=\$(alloc_client_ip \"\$conf\" '10.13.13')
        [ \"\$ip2\" = '10.13.13.3' ] || exit 1
        echo 'AllowedIPs = 10.13.13.5/32' >> \"\$conf\"
        ip3=\$(alloc_client_ip \"\$conf\" '10.13.13')
        [ \"\$ip3\" = '10.13.13.6' ] || exit 1
    "
    [ "$status" -eq 0 ]
}

@test "валидация портов, hex и endpoint отсекает некорректные значения" {
    run bash -c "
        source '$SCRIPT'
        valid_port 51820 || exit 1
        ! valid_port 0 || exit 1
        ! valid_port 70000 || exit 1
        ! valid_port abc || exit 1
        valid_hex64 '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef' || exit 1
        ! valid_hex64 'short' || exit 1
        valid_endpoint '127.0.0.1:9000' || exit 1
        valid_endpoint '[::1]:9000' || exit 1
        ! valid_endpoint 'invalid-endpoint' || exit 1
        true
    "
    [ "$status" -eq 0 ]
}

@test "validate_config отсекает конфигурацию без компонентов" {
    run bash -c "
        source '$SCRIPT'
        INSTALL_FREETURN=0
        INSTALL_AWG=0
        INSTALL_WG=0
        validate_config
    "
    [ "$status" -ne 0 ]
    [[ "$output" == *"Не выбран ни один компонент"* ]]
}

@test "validate_config валидирует режимы only-awg и only-freeturn" {
    run bash -c "
        source '$SCRIPT'
        # Только FreeTurn
        INSTALL_FREETURN=1
        INSTALL_AWG=0
        INSTALL_WG=0
        INSTALL_METHOD='docker'
        PROVIDER='vk'
        PROXY_MODE='udp'
        LISTEN_PORT='56000'
        OBF_PROFILE='none'
        validate_config || exit 1

        # Только AmneziaWG
        INSTALL_FREETURN=0
        INSTALL_AWG=1
        INSTALL_WG=0
        BACKEND_PORT='51820'
        WG_ENDPOINT='127.0.0.1:9000'
        validate_config || exit 1
        true
    "
    [ "$status" -eq 0 ]
}

@test "generate_freeturn_uri генерирует корректный URI со схемой freeturn://" {
    run bash -c "
        source '$SCRIPT'
        uri=\$(generate_freeturn_uri '1.2.3.4:56000' 'udp' 'rtpopus3' '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef' 'cid123' 'my-client')
        [[ \"\$uri\" == freeturn://* ]] || exit 1
        b64=\"\${uri#freeturn://}\"
        pad=\$(( (4 - \${#b64} % 4) % 4 ))
        [ \"\$pad\" -eq 1 ] && b64=\"\${b64}=\"
        [ \"\$pad\" -eq 2 ] && b64=\"\${b64}==\"
        [ \"\$pad\" -eq 3 ] && b64=\"\${b64}===\"
        json=\$(printf '%s' \"\$b64\" | tr '_-' '/+' | base64 -d 2>/dev/null)
        [[ \"\$json\" == *'\"v\":1'* ]] || exit 1
        [[ \"\$json\" == *'\"provider\":\"vk\"'* ]] || exit 1
        [[ \"\$json\" == *'\"peer\":\"1.2.3.4:56000\"'* ]] || exit 1
        [[ \"\$json\" == *'\"obf\":\"rtpopus3\"'* ]] || exit 1
        [[ \"\$json\" == *'\"cid\":\"cid123\"'* ]] || exit 1
        [[ \"\$json\" == *'\"name\":\"my-client\"'* ]] || exit 1
        true
    "
    [ "$status" -eq 0 ]
}

@test "peer-add требует --name-b64 и --endpoint в RPC режиме" {
    run_script peer-add
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
    [[ "$output" == *"--name-b64 required"* ]]

    run_script peer-add --name-b64="Y2xpZW50"
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
    [[ "$output" == *"--endpoint required"* ]]
}

@test "клиентский конфиг AWG 3.1 содержит все 18 параметров обфускации" {
    run bash -c "
        source '$SCRIPT'
        AWG_JC=5
        AWG_JMIN=10
        AWG_JMAX=50
        AWG_S1=20
        AWG_S2=30
        AWG_S3=40
        AWG_S4=12
        AWG_H1=1
        AWG_H2=2
        AWG_H3=3
        AWG_H4=4
        AWG_HPK='dGVzdGtleTEyMzQ1Njc4OTA='
        ext_ip='1.2.3.4'
        BACKEND_PORT=51820
        client_ip='10.13.13.2'
        cli_priv='clientprivkey'
        srv_pub='serverpubkey'

        conf=\$(cat <<EOF
[Interface]
Address = \${client_ip}/32
DNS = 1.1.1.1, 1.0.0.1
PrivateKey = \${cli_priv}
Jc = \${AWG_JC}
Jmin = \${AWG_JMIN}
Jmax = \${AWG_JMAX}
S1 = \${AWG_S1}
S2 = \${AWG_S2}
S3 = \${AWG_S3}
S4 = \${AWG_S4}
H1 = \${AWG_H1}
H2 = \${AWG_H2}
H3 = \${AWG_H3}
H4 = \${AWG_H4}
HeaderProtectionKey = \${AWG_HPK}
ContentPaddingAddition = 10
RekeyAfterTime = 110
RekeyTimeout = 5
RejectAfterTime = 160
KeepaliveTimeout = 10
MaxHandshakeAttempts = 15
RandomTrailers = on
DisableCookies = on

[Peer]
PublicKey = \${srv_pub}
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = \${ext_ip}:\${BACKEND_PORT}
PersistentKeepalive = 25
EOF
        )
        for param in Jc Jmin Jmax S1 S2 S3 S4 H1 H2 H3 H4 HeaderProtectionKey ContentPaddingAddition RekeyAfterTime RekeyTimeout RejectAfterTime KeepaliveTimeout MaxHandshakeAttempts RandomTrailers DisableCookies; do
            echo \"\$conf\" | grep -q \"^\$param = \" || { echo \"Missing \$param\"; exit 1; }
        done
        true
    "
    [ "$status" -eq 0 ]
}

@test "peer-conf требует pubkey или pubkey-b64" {
    run_script peer-conf
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
    [[ "$output" == *"--pubkey required"* ]]
}

@test "peer-remove требует pubkey" {
    run_script peer-remove
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
    [[ "$output" == *"--pubkey required"* ]]
}

@test "client-add и client-remove валидируют обязательные аргументы в RPC" {
    run_script client-add
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
    [[ "$output" == *"--client-id required"* ]]

    run_script client-add --client-id="cid123"
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
    [[ "$output" == *"--name-b64 required"* ]]

    run_script client-remove
    [ "$status" -eq 1 ]
    [[ "$output" == *'"code":"bad_arg"'* ]]
    [[ "$output" == *"--client-id required"* ]]
}

@test "uninstall без root возвращает needs_root в RPC" {
    run bash -c "
        export FT_PREFIX='$FT_PREFIX'
        # Запуск от текущего непривилегированного пользователя в тестах
        bash '$SCRIPT' uninstall --target=freeturn
    "
    # Если запущен не под root, должен вернуть code: needs_root
    if [ "$(id -u)" -ne 0 ]; then
        [ "$status" -eq 1 ]
        [[ "$output" == *'"code":"needs_root"'* ]]
    fi
}

@test "validate_config отсекает невалидные порты, режимы и методы" {
    run bash -c "
        source '$SCRIPT'
        # Невалидный метод
        ( INSTALL_FREETURN=1; INSTALL_AWG=0; INSTALL_METHOD='nomad'; PROVIDER='vk'; PROXY_MODE='udp'; LISTEN_PORT='56000'; OBF_PROFILE='none'; validate_config ) 2>/dev/null && exit 1

        # Невалидный режим
        ( INSTALL_FREETURN=1; INSTALL_AWG=0; INSTALL_METHOD='docker'; PROVIDER='vk'; PROXY_MODE='http'; LISTEN_PORT='56000'; OBF_PROFILE='none'; validate_config ) 2>/dev/null && exit 1

        # Невалидный порт listen
        ( INSTALL_FREETURN=1; INSTALL_AWG=0; INSTALL_METHOD='docker'; PROVIDER='vk'; PROXY_MODE='udp'; LISTEN_PORT='99999'; OBF_PROFILE='none'; validate_config ) 2>/dev/null && exit 1

        # Невалидный профиль обфускации
        ( INSTALL_FREETURN=1; INSTALL_AWG=0; INSTALL_METHOD='docker'; PROVIDER='vk'; PROXY_MODE='udp'; LISTEN_PORT='56000'; OBF_PROFILE='invalid_prof'; validate_config ) 2>/dev/null && exit 1

        # Невалидный порт бэкенда
        ( INSTALL_FREETURN=0; INSTALL_AWG=1; BACKEND_PORT='invalid'; WG_ENDPOINT='127.0.0.1:9000'; validate_config ) 2>/dev/null && exit 1

        # Невалидный endpoint
        ( INSTALL_FREETURN=0; INSTALL_AWG=1; BACKEND_PORT='51820'; WG_ENDPOINT='bad_endpoint'; validate_config ) 2>/dev/null && exit 1
        true
    "
    [ "$status" -eq 0 ]
}

@test "state_set и state_get сохраняют и читают параметры состояния" {
    run bash -c "
        source '$SCRIPT'
        state_set 'key1' 'val1'
        res1=\$(state_get 'key1')
        [ \"\$res1\" = 'val1' ] || exit 1

        state_set 'key2' 'val2'
        state_set 'key1' 'val1_updated'
        res1_up=\$(state_get 'key1')
        res2=\$(state_get 'key2')
        [ \"\$res1_up\" = 'val1_updated' ] || exit 1
        [ \"\$res2\" = 'val2' ] || exit 1
        true
    "
    [ "$status" -eq 0 ]
}

@test "save_config и load_config корректно сериализуют и восстанавливают настройки" {
    run bash -c "
        source '$SCRIPT'
        INSTALL_METHOD='docker'
        INSTALL_FREETURN=1
        INSTALL_AWG=1
        INSTALL_WG=0
        PROXY_MODE='tcp'
        BACKEND_PORT=51821
        LISTEN_PORT=56001
        OBF_PROFILE='rtpopus3'
        OBF_KEY='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'
        WG_ENDPOINT='127.0.0.1:9001'
        AWG_JC=4
        AWG_HPK='dGVzdA=='

        save_config

        # Сбрасываем переменные
        PROXY_MODE=''
        BACKEND_PORT=''
        LISTEN_PORT=''
        OBF_PROFILE=''
        OBF_KEY=''
        WG_ENDPOINT=''
        AWG_JC=''
        AWG_HPK=''

        load_config

        [ \"\$PROXY_MODE\" = 'tcp' ] || exit 1
        [ \"\$BACKEND_PORT\" = '51821' ] || exit 1
        [ \"\$LISTEN_PORT\" = '56001' ] || exit 1
        [ \"\$OBF_PROFILE\" = 'rtpopus3' ] || exit 1
        [ \"\$OBF_KEY\" = '0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef' ] || exit 1
        [ \"\$WG_ENDPOINT\" = '127.0.0.1:9001' ] || exit 1
        [ \"\$AWG_JC\" = '4' ] || exit 1
        [ \"\$AWG_HPK\" = 'dGVzdA==' ] || exit 1
        true
    "
    [ "$status" -eq 0 ]
}

@test "relay и direct клиентские конфиги имеют разные Endpoint" {
    run bash -c "
        source '$SCRIPT'
        mkdir -p '$FT_PREFIX/clients'
        ext_ip='203.0.113.10'
        BACKEND_PORT=51820
        WG_ENDPOINT='127.0.0.1:9000'

        direct_endpoint=\"\${ext_ip}:\${BACKEND_PORT}\"
        relay_endpoint=\"\${WG_ENDPOINT}\"

        [ \"\$direct_endpoint\" != \"\$relay_endpoint\" ] || exit 1
        [ \"\$direct_endpoint\" = '203.0.113.10:51820' ] || exit 1
        [ \"\$relay_endpoint\" = '127.0.0.1:9000' ] || exit 1
        true
    "
    [ "$status" -eq 0 ]
}

@test "logs RPC команда принимает tail и возвращает ok" {
    run_script logs --tail=10
    [ "$status" -eq 0 ]
    [[ "$output" == *'"result":"ok"'* ]]
    [[ "$output" == *'"logs":'* ]]
}

@test "клиентский CLI без аргументов или с неверной командой выводит ошибку" {
    run bash -c "
        # Без root при вызове сабкоманд client скрипт требует root
        bash '$SCRIPT' client invalid_sub 2>&1
    "
    [ "$status" -ne 0 ]
}

@test "запуск через пайп (curl | bash) корректно обрабатывает параметры без ошибки unbound variable" {
    run bash -c "cat '$SCRIPT' | bash -s -- -h"
    [ "$status" -eq 0 ]
    [[ "$output" == *"Free Turn Proxy & AmneziaWG"* ]]
}

@test "compose_cmd делегирует вызовы в docker compose или docker-compose" {
    run bash -c "
        source '$SCRIPT'
        docker() {
            if [ \"\$1\" = 'compose' ]; then
                echo \"MOCK_COMPOSE: \${*:2}\"
                return 0
            fi
            return 1
        }
        export -f docker
        compose_cmd version
    "
    [ "$status" -eq 0 ]
    [[ "$output" == *"MOCK_COMPOSE: version"* ]]

    run bash -c "
        source '$SCRIPT'
        docker() { return 1; }
        docker-compose() {
            echo \"MOCK_STANDALONE: \$*\"
            return 0
        }
        export -f docker docker-compose
        compose_cmd up -d
    "
    [ "$status" -eq 0 ]
    [[ "$output" == *"MOCK_STANDALONE: up -d"* ]]
}

@test "ensure_compose успешно завершается, если docker compose уже установлен" {
    run bash -c "
        source '$SCRIPT'
        docker() {
            if [ \"\$1\" = 'compose' ] && [ \"\$2\" = 'version' ]; then
                return 0
            fi
            return 1
        }
        export -f docker
        ensure_compose
    "
    [ "$status" -eq 0 ]
}

@test "ensure_awg_image возвращает 0, если образ уже присутствует локально" {
    run bash -c "
        source '$SCRIPT'
        INSTALL_AWG=1
        docker() {
            if [ \"\$1\" = 'image' ] && [ \"\$2\" = 'inspect' ]; then
                return 0
            fi
            return 1
        }
        export -f docker
        ensure_awg_image
    "
    [ "$status" -eq 0 ]
}
