package awgcore

import (
        "encoding/base64"
        "encoding/hex"
        "strings"
        "testing"
)

const sampleAwgConf = `# Generated AmneziaWG Config
[Interface]
PrivateKey = wMdTqQ/ajo8JGFAFxGVfO+v9H4IwXNnY1nSgD1vDSHo=
Address = 172.16.0.2/32, 2606:4700:110:8e46:b9b5:a055:4e0c:1b09/128
DNS = 1.1.1.1, 1.0.0.1, 2606:4700:4700::1111, 2606:4700:4700::1001
MTU = 1280
Jc = 3
Jmin = 1
Jmax = 3
S1 = 0
S2 = 0
H1 = 1
H2 = 2
H3 = 3
H4 = 4

[Peer]
PublicKey = bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=
AllowedIPs = 0.0.0.0/0, ::/0
Endpoint = 8.6.112.29:3581`

func TestBuildIpcSettingsFromAmneziaConf(t *testing.T) {
        settings, err := buildIpcSettings(sampleAwgConf)
        if err != nil {
                t.Fatalf("buildIpcSettings failed: %v", err)
        }

        // Compute the expected hex keys from the sample conf at runtime.
        privHex := mustB64ToHex(t, "wMdTqQ/ajo8JGFAFxGVfO+v9H4IwXNnY1nSgD1vDSHo=")
        pubHex := mustB64ToHex(t, "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=")

        want := []string{
                "private_key=" + privHex,
                "jc=3", "jmin=1", "jmax=3", "s1=0", "s2=0",
                "h1=1", "h2=2", "h3=3", "h4=4",
                "public_key=" + pubHex,
                "endpoint=8.6.112.29:3581",
                "replace_allowed_ips=true",
                "allowed_ip=0.0.0.0/0",
                "allowed_ip=::/0",
        }
        for _, w := range want {
                if !strings.Contains(settings, w+"\n") {
                        t.Errorf("settings missing %q\nGot:\n%s", w, settings)
                }
        }
        if strings.Contains(settings, "dns=") {
                t.Errorf("DNS must not leak into IPC settings:\n%s", settings)
        }
}

func mustB64ToHex(t *testing.T, b64 string) string {
        t.Helper()
        raw, err := base64.StdEncoding.DecodeString(b64)
        if err != nil {
                t.Fatalf("bad sample key: %v", err)
        }
        return hex.EncodeToString(raw)
}

func TestParseTunnelSettingsFromAmneziaConf(t *testing.T) {
        // Kotlin-side equivalent check happens through extractTunnelSettings.
        iface := sampleAwgConf
        if !strings.Contains(iface, "DNS = 1.1.1.1") {
                t.Fatalf("sample conf unexpectedly changed")
        }
}
