package guardian

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"encoding/pem"
	"testing"
)

func TestSignedReleaseRejectsTamperingAndOtherSigningKey(t *testing.T) {
	key, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	der, _ := x509.MarshalPKIXPublicKey(&key.PublicKey)
	pub := pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: der})
	payload := []byte(`{"schema":1,"version":"1.0.0","build":1,"protocol":1,"artifacts":[]}`)
	hash := sha256.Sum256(payload)
	signature, _ := ecdsa.SignASN1(rand.Reader, key, hash[:])
	env := Envelope{Payload: base64.StdEncoding.EncodeToString(payload), Signature: base64.StdEncoding.EncodeToString(signature)}
	data, _ := json.Marshal(env)
	if _, e := verifyReleaseWithKey(data, pub); e != nil {
		t.Fatal(e)
	}
	env.Payload = base64.StdEncoding.EncodeToString(append(payload, ' '))
	data, _ = json.Marshal(env)
	if _, e := verifyReleaseWithKey(data, pub); e == nil {
		t.Fatal("tampered payload trusted")
	}
	other, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	otherDER, _ := x509.MarshalPKIXPublicKey(&other.PublicKey)
	otherPub := pem.EncodeToMemory(&pem.Block{Type: "PUBLIC KEY", Bytes: otherDER})
	env.Payload = base64.StdEncoding.EncodeToString(payload)
	data, _ = json.Marshal(env)
	if _, e := verifyReleaseWithKey(data, otherPub); e == nil {
		t.Fatal("different signing key trusted")
	}
}
