package libv2ray

// Zero VPN: AmneziaWG engine bindings.
//
// These wrappers expose the embedded amneziawg-go engine (package awgcore)
// through the same gomobile surface as the Xray core API, so both runtimes
// share one libgojni.so. See awgcore/awgcore.go for the full documentation.

import "github.com/2dust/AndroidLibXrayLite/awgcore"

// AwgTurnOn starts the AmneziaWG engine on the given VpnService TUN fd.
// wgQuickConf is a standard [Interface]/[Peer] configuration, with or without
// Amnezia obfuscation parameters (Jc/Jmin/Jmax/S1-S4/H1-H4). Returns a
// positive handle used by the other Awg* functions.
func AwgTurnOn(ifName string, tunFd int32, wgQuickConf string) (int64, error) {
	return awgcore.TurnOn(ifName, tunFd, wgQuickConf)
}

// AwgTurnOff stops the engine and releases all resources.
func AwgTurnOff(handle int64) error {
	return awgcore.TurnOff(handle)
}

// AwgGetSocketV4 returns the fd of the engine's IPv4 UDP socket for
// VpnService.protect(); -1 when the socket does not exist (yet).
func AwgGetSocketV4(handle int64) (int, error) {
	return awgcore.GetSocketV4(handle)
}

// AwgGetSocketV6 is the IPv6 variant of AwgGetSocketV4.
func AwgGetSocketV6(handle int64) (int, error) {
	return awgcore.GetSocketV6(handle)
}

// AwgLastHandshakeSec returns the UNIX timestamp of the last completed
// handshake (0 = none), proving whether the tunnel is alive.
func AwgLastHandshakeSec(handle int64) (int64, error) {
	return awgcore.LastHandshakeSec(handle)
}
