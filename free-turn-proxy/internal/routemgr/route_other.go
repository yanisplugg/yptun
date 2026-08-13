//go:build (!windows && !linux && !darwin) || android

package routemgr

func discoverGateway() (string, error) {
	return "", nil
}

func addRoute(ip, gateway string) error {
	return nil
}

func delRoute(ip string) error {
	return nil
}
