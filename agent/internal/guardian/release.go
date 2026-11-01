package guardian

import (
	"alicebridge/agent/assets"
	"crypto/ecdsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"os"
	"runtime"
)

type Artifact struct {
	Arch   string `json:"arch"`
	File   string `json:"file"`
	SHA256 string `json:"sha256"`
	Size   int64  `json:"size"`
}
type Release struct {
	Schema    int        `json:"schema"`
	Version   string     `json:"version"`
	Build     int        `json:"build"`
	Protocol  int        `json:"protocol"`
	Artifacts []Artifact `json:"artifacts"`
	Changes   []string   `json:"changes"`
}
type Envelope struct {
	Payload   string `json:"payload"`
	Signature string `json:"signature"`
}

func VerifyRelease(data []byte) (Release, error) {
	keyData, _ := assets.Scripts.ReadFile("signing-public.pem")
	return verifyReleaseWithKey(data, keyData)
}
func verifyReleaseWithKey(data, keyData []byte) (Release, error) {
	var out Release
	var env Envelope
	if len(data) > 65536 || json.Unmarshal(data, &env) != nil {
		return out, errors.New("invalid release manifest")
	}
	payload, e := base64.StdEncoding.DecodeString(env.Payload)
	if e != nil {
		return out, e
	}
	sig, e := base64.StdEncoding.DecodeString(env.Signature)
	if e != nil {
		return out, e
	}
	block, _ := pem.Decode(keyData)
	if block == nil {
		return out, errors.New("missing signing key")
	}
	key, e := x509.ParsePKIXPublicKey(block.Bytes)
	if e != nil {
		return out, e
	}
	ec, ok := key.(*ecdsa.PublicKey)
	if !ok {
		return out, errors.New("wrong signing key type")
	}
	digest := sha256.Sum256(payload)
	if !ecdsa.VerifyASN1(ec, digest[:], sig) {
		return out, errors.New("release signature does not match")
	}
	if e = json.Unmarshal(payload, &out); e != nil {
		return out, e
	}
	if out.Schema != 1 || out.Protocol != Protocol || out.Build < 1 {
		return out, errors.New("unsupported release schema")
	}
	return out, nil
}
func VerifyExecutable(manifest, executable string, build int) (Release, error) {
	data, e := os.ReadFile(manifest)
	if e != nil {
		return Release{}, e
	}
	r, e := VerifyRelease(data)
	if e != nil {
		return r, e
	}
	if r.Build != build {
		return r, errors.New("manifest build differs from executable")
	}
	var artifact *Artifact
	for i := range r.Artifacts {
		if r.Artifacts[i].Arch == runtime.GOARCH {
			artifact = &r.Artifacts[i]
			break
		}
	}
	if artifact == nil {
		return r, errors.New("unsupported architecture")
	}
	st, e := os.Lstat(executable)
	if e != nil || !st.Mode().IsRegular() || st.Size() != artifact.Size || st.Size() > 32<<20 {
		return r, errors.New("invalid executable size or type")
	}
	data, e = os.ReadFile(executable)
	if e != nil {
		return r, e
	}
	sum := sha256.Sum256(data)
	if hex.EncodeToString(sum[:]) != artifact.SHA256 {
		return r, errors.New("executable hash differs from signed release")
	}
	return r, nil
}
