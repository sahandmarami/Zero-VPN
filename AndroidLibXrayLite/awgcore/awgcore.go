// Package awgcore embeds the AmneziaWG engine (amneziawg-go) for Zero VPN so
// the app can connect to WireGuard servers that use the Amnezia obfuscation
// extensions (Jc/Jmin/Jmax, S1-S4, H1-H4, I1-I5, ...) as well as plain
// WireGuard.
//
// The engine runs directly on the VpnService TUN file descriptor, completely
// bypassing the Xray-core wireguard outbound (which does not implement the
// Amnezia wire format). Socket protection is performed by the host app via
// GetSocketV4/GetSocketV6 + VpnService.protect().
package awgcore

import (
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"

	"github.com/amnezia-vpn/amneziawg-go/v3/conn"
	"github.com/amnezia-vpn/amneziawg-go/v3/device"
	"github.com/amnezia-vpn/amneziawg-go/v3/tun"
	"golang.org/x/sys/unix"
)

type awgInstance struct {
	dev *device.Device
}

var (
	handleMutex sync.Mutex
	nextHandle  int64 = 1000
	activeHandles     = make(map[int64]*awgInstance)
)

// TurnOn starts the AmneziaWG engine on the given VpnService TUN fd.
//
// wgQuickConf is a standard [Interface]/[Peer] configuration file, with or
// without AmneziaWG obfuscation parameters. With no obfuscation parameters
// the engine behaves exactly like standard WireGuard (the device defaults to
// the standard message type headers 1..4 and no junk packets).
//
// The fd is duplicated internally; the caller keeps ownership of the original
// descriptor and must NOT read/write it while the engine is running.
//
// Returns a positive handle on success. Pass the handle to the other
// functions. TurnOff releases the handle.
func TurnOn(ifName string, tunFd int32, wgQuickConf string) (int64, error) {
	if tunFd <= 0 {
		return 0, fmt.Errorf("invalid tun fd %d", tunFd)
	}
	if strings.TrimSpace(wgQuickConf) == "" {
		return 0, fmt.Errorf("empty configuration")
	}

	dupFd, err := unix.Dup(int(tunFd))
	if err != nil {
		return 0, fmt.Errorf("failed to dup tun fd: %w", err)
	}
	unix.CloseOnExec(dupFd)

	tunFile := os.NewFile(uintptr(dupFd), ifName)
	tunDevice, err := tun.CreateTUNFromFile(tunFile, device.DefaultMTU)
	if err != nil {
		_ = unix.Close(dupFd)
		return 0, fmt.Errorf("failed to attach to tun: %w", err)
	}

	logger := device.NewLogger(device.LogLevelVerbose, "awg: ")
	dev := device.NewDevice(tunDevice, conn.NewDefaultBind(), logger)

	settings, err := buildIpcSettings(wgQuickConf)
	if err != nil {
		dev.Close()
		return 0, err
	}
	if err := dev.IpcSet(settings); err != nil {
		dev.Close()
		return 0, fmt.Errorf("failed to apply settings: %w", err)
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		return 0, fmt.Errorf("failed to bring device up: %w", err)
	}

	handleMutex.Lock()
	defer handleMutex.Unlock()
	handle := nextHandle
	nextHandle++
	activeHandles[handle] = &awgInstance{dev: dev}
	return handle, nil
}

// TurnOff stops the engine and releases all resources (sockets and the
// duplicated tun fd). It is safe to call with a stale or zero handle.
func TurnOff(handle int64) error {
	handleMutex.Lock()
	inst, ok := activeHandles[handle]
	delete(activeHandles, handle)
	handleMutex.Unlock()

	if !ok || inst == nil {
		return nil
	}
	inst.dev.Close()
	return nil
}

// GetSocketV4 returns the fd of the engine's IPv4 UDP socket so the host app
// can call VpnService.protect() on it. Returns -1 when the socket does not
// exist (yet). The fd is owned by the engine; protect() does not close it.
func GetSocketV4(handle int64) (int, error) {
	inst := getInstance(handle)
	if inst == nil {
		return -1, fmt.Errorf("bad handle")
	}
	bind, ok := inst.dev.Bind().(conn.PeekLookAtSocketFd)
	if !ok {
		return -1, fmt.Errorf("bind does not expose sockets")
	}
	return bind.PeekLookAtSocketFd4()
}

// GetSocketV6 is the IPv6 variant of GetSocketV4.
func GetSocketV6(handle int64) (int, error) {
	inst := getInstance(handle)
	if inst == nil {
		return -1, fmt.Errorf("bad handle")
	}
	bind, ok := inst.dev.Bind().(conn.PeekLookAtSocketFd)
	if !ok {
		return -1, fmt.Errorf("bind does not expose sockets")
	}
	return bind.PeekLookAtSocketFd6()
}

// LastHandshakeSec returns the UNIX timestamp of the peer's last completed
// handshake, 0 when no handshake happened yet. A small age proves the tunnel
// is alive.
func LastHandshakeSec(handle int64) (int64, error) {
	inst := getInstance(handle)
	if inst == nil {
		return 0, fmt.Errorf("bad handle")
	}
	dump, err := inst.dev.IpcGet()
	if err != nil {
		return 0, err
	}
	for _, line := range strings.Split(dump, "\n") {
		key, value, ok := strings.Cut(line, "=")
		if ok && key == "last_handshake_time_sec" {
			sec, err := strconv.ParseInt(strings.TrimSpace(value), 10, 64)
			if err != nil {
				return 0, nil
			}
			return sec, nil
		}
	}
	return 0, nil
}

func getInstance(handle int64) *awgInstance {
	handleMutex.Lock()
	defer handleMutex.Unlock()
	return activeHandles[handle]
}

// ---------------------------------------------------------------------------
// wg-quick configuration -> device IPC settings
// ---------------------------------------------------------------------------

const awgDeviceKeys = "jc jmin jmax s1 s2 s3 s4 h1 h2 h3 h4 i1 i2 i3 i4 i5"

// device keys whose wg-quick spelling differs from the IPC spelling.
var keyNameMap = map[string]string{
	"disablecookies":         "disable_cookies",
	"randomtrailers":         "random_trailers",
	"headerprotectionkey":    "header_protection_key",
	"contentpaddingaddition": "content_padding_addition",
}

// buildIpcSettings converts a wg-quick style configuration (INI text with
// [Interface]/[Peer] sections, base64 keys, comma-separated lists) into the
// newline separated key=value IPC representation understood by
// device.Device.IpcSet (hex keys, one allowed_ip per line, ...).
func buildIpcSettings(wgQuickConf string) (string, error) {
	iface, peers, err := parseQuickConf(wgQuickConf)
	if err != nil {
		return "", err
	}

	var sb strings.Builder

	privHex, err := base64ToHex(iface["privatekey"])
	if err != nil {
		return "", fmt.Errorf("invalid PrivateKey: %w", err)
	}
	sb.WriteString("private_key=" + privHex + "\n")

	if lp := strings.TrimSpace(iface["listenport"]); lp != "" {
		if _, err := strconv.ParseUint(lp, 10, 16); err != nil {
			return "", fmt.Errorf("invalid ListenPort %q", lp)
		}
		sb.WriteString("listen_port=" + lp + "\n")
	}

	// AmneziaWG obfuscation parameters (case-insensitive in the conf).
	for _, k := range strings.Fields(awgDeviceKeys) {
		if v := strings.TrimSpace(iface[k]); v != "" {
			sb.WriteString(k + "=" + v + "\n")
		}
	}
	for confKey, ipcKey := range keyNameMap {
		if v := strings.TrimSpace(iface[confKey]); v != "" {
			sb.WriteString(ipcKey + "=" + v + "\n")
		}
	}

	if len(peers) == 0 {
		return "", fmt.Errorf("configuration has no [Peer] section")
	}
	for _, peer := range peers {
		pubHex, err := base64ToHex(peer["publickey"])
		if err != nil {
			return "", fmt.Errorf("invalid PublicKey: %w", err)
		}
		sb.WriteString("public_key=" + pubHex + "\n")

		if psk := strings.TrimSpace(peer["presharedkey"]); psk != "" {
			pskHex, err := base64ToHex(psk)
			if err != nil {
				return "", fmt.Errorf("invalid PresharedKey: %w", err)
			}
			sb.WriteString("preshared_key=" + pskHex + "\n")
		}

		if ep := strings.TrimSpace(peer["endpoint"]); ep != "" {
			sb.WriteString("endpoint=" + ep + "\n")
		}

		if ka := strings.TrimSpace(peer["persistentkeepalive"]); ka != "" {
			sb.WriteString("persistent_keepalive_interval=" + ka + "\n")
		}

		sb.WriteString("replace_allowed_ips=true\n")
		for _, ip := range strings.Split(peer["allowedips"], ",") {
			ip = strings.TrimSpace(ip)
			if ip != "" {
				sb.WriteString("allowed_ip=" + ip + "\n")
			}
		}
	}

	return sb.String(), nil
}

// parseQuickConf splits a wg-quick configuration into interface parameters
// and peer parameter maps. Keys are lower-cased, values are trimmed. Both
// [Interface] and [Peer] section headers are matched case-insensitively;
// comments and blank lines are ignored.
func parseQuickConf(conf string) (map[string]string, []map[string]string, error) {
	iface := make(map[string]string)
	var peers []map[string]string
	var current map[string]string
	section := ""

	for _, raw := range strings.Split(strings.ReplaceAll(conf, "\r", ""), "\n") {
		line := strings.TrimSpace(raw)
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		lower := strings.ToLower(line)
		if strings.HasPrefix(lower, "[interface]") {
			current = iface
			section = "interface"
			continue
		}
		if strings.HasPrefix(lower, "[peer]") {
			current = make(map[string]string)
			peers = append(peers, current)
			section = "peer"
			continue
		}
		if current == nil {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		current[strings.ToLower(strings.TrimSpace(key))] = strings.TrimSpace(value)
	}

	if section == "" {
		return nil, nil, fmt.Errorf("no [Interface] section found")
	}
	return iface, peers, nil
}

func base64ToHex(s string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(s))
	if err != nil {
		return "", err
	}
	if len(raw) != 32 {
		return "", fmt.Errorf("key must be 32 bytes, got %d", len(raw))
	}
	return hex.EncodeToString(raw), nil
}
