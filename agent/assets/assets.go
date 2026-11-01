package assets

import "embed"

//go:embed *.sh *.pem *.txt *.md
var Scripts embed.FS
