package torrServer

import (
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"

	"github.com/gin-contrib/location/v2"
	"github.com/gin-gonic/gin"

	"server/log"
	"server/netbind"
	"server/settings"
	"server/torr"
	"server/torr/utils"
	"server/version"
	"server/web/api"
)

const tag = "st-1"

const loopback = "127.0.0.1"

var (
	mu      sync.Mutex
	bts     = torr.NewBTS()
	srv     *http.Server
	port    int
	running bool
	done    = make(chan struct{})
)

func init() {
	version.Version = "ServeTorr torrserver " + tag
	gin.SetMode(gin.ReleaseMode)
}

func StartTorrentServer(pathdb string, requestedPort int) (result int) {
	mu.Lock()
	defer mu.Unlock()
	if running {
		return port
	}

	dbOpen := false
	var ln net.Listener
	defer func() {
		if r := recover(); r != nil {
			log.TLogln("ServeTorr start panic:", r, string(debug.Stack()))
			result = -1
		}
		if result < 0 {
			if ln != nil {
				ln.Close()
			}
			bts.Disconnect()
			if dbOpen {
				settings.CloseDB()
			}
		}
	}()

	settings.Embedded = true
	settings.EmbeddedStop = func() { go StopTorrentServer() }
	settings.Path = pathdb
	settings.IPs = []string{loopback}
	settings.Ssl = false
	settings.HttpAuth = false
	settings.Args = &settings.ExecArgs{
		Port: strconv.Itoa(requestedPort),
		IPs:  settings.IPs,
		Path: pathdb,
	}

	if err := settings.InitSets(false, false); err != nil {
		log.TLogln("ServeTorr settings:", err)
		return -1
	}
	dbOpen = true

	var err error
	ln, err = netbind.Listen(settings.IPs, settings.Args.Port)
	if err != nil {
		log.TLogln("ServeTorr listen:", err)
		return -1
	}
	realPort := ln.Addr().(*net.TCPAddr).Port
	settings.Port = strconv.Itoa(realPort)
	settings.Args.Port = settings.Port

	log.TLogln("Start TorrServer " + version.Version + " torrent " + version.GetTorrentVersion())
	if err := bts.Connect(); err != nil {
		log.TLogln("ServeTorr BTS.Connect:", err)
		return -1
	}
	go cleanCache()

	route := gin.New()
	route.Use(log.WebLogger(), gin.Recovery(), rejectBrowsers(), location.Default())
	route.GET("/echo", func(c *gin.Context) { c.String(http.StatusOK, "%v", version.Version) })
	api.SetupRoute(route)

	server := &http.Server{Handler: route}
	go server.Serve(ln)
	srv = server
	settings.InternalPort = settings.Port
	log.TLogln("Start http server at", ln.Addr())

	port = realPort
	running = true
	done = make(chan struct{})
	return realPort
}

func StopTorrentServer() {
	mu.Lock()
	defer mu.Unlock()
	if !running {
		return
	}
	defer func() {
		if r := recover(); r != nil {
			log.TLogln("ServeTorr stop panic:", r)
		}
		running = false
		port = 0
		close(done)
	}()
	if srv != nil {
		srv.Close()
		srv = nil
	}
	settings.InternalPort = ""
	bts.Disconnect()
	settings.CloseDB()
}

func WaitTorrentServer() {
	mu.Lock()
	ch := done
	active := running
	mu.Unlock()
	if active {
		<-ch
	}
}

func AddTrackers(trackers string) {
	var list []string
	for _, line := range strings.Split(trackers, "\n") {
		line = strings.Trim(line, " ,\r")
		if line != "" {
			list = append(list, line)
		}
	}
	utils.SetDefTrackers(list)
}

func rejectBrowsers() gin.HandlerFunc {
	return func(c *gin.Context) {
		h := c.Request.Header
		if len(h.Values("Origin")) > 0 || len(h.Values("Sec-Fetch-Site")) > 0 {
			c.AbortWithStatus(http.StatusForbidden)
			return
		}
		c.Next()
	}
}

func cleanCache() {
	sets := settings.BTsets
	if sets == nil || !sets.UseDisk || sets.TorrentsSavePath == "/" || sets.TorrentsSavePath == "" {
		return
	}
	dirs, err := os.ReadDir(sets.TorrentsSavePath)
	if err != nil {
		return
	}
	keep := map[string]bool{}
	if !sets.RemoveCacheOnDrop {
		for _, t := range settings.ListTorrent() {
			keep[t.InfoHash.HexString()] = true
		}
	}
	for _, d := range dirs {
		if !d.IsDir() || len(d.Name()) != 40 || keep[d.Name()] {
			continue
		}
		dir := filepath.Join(sets.TorrentsSavePath, d.Name())
		log.TLogln("Remove unused cache:", d.Name())
		files, err := os.ReadDir(dir)
		if err == nil {
			for _, f := range files {
				os.Remove(filepath.Join(dir, f.Name()))
			}
		}
		os.Remove(dir)
	}
}
