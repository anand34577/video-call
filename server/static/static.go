package static

import (
	"embed"
	"io/fs"
)

// dist is populated by the frontend build (web/dist copied here, or built
// inside Docker). A placeholder keeps compiles working pre-build.
//
//go:embed all:dist
var distFS embed.FS

func Dist() fs.FS {
	sub, err := fs.Sub(distFS, "dist")
	if err != nil {
		panic(err)
	}
	return sub
}
