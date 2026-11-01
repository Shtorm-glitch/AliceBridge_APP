package guardian

import (
	"os"
	"path/filepath"
	"testing"
)

func TestPrivateDirectoryAllowsFirmwareParentAlias(t *testing.T) {
	root := t.TempDir()
	volume := filepath.Join(root, "persistent")
	os.Mkdir(volume, 0755)
	alias := filepath.Join(root, "quasar")
	if e := os.Symlink(volume, alias); e != nil {
		t.Fatal(e)
	}
	base := filepath.Join(alias, "alicebridge-agent")
	if e := privateDirectory(base); e != nil {
		t.Fatal(e)
	}
	st, e := os.Lstat(filepath.Join(volume, "alicebridge-agent"))
	if e != nil || !st.IsDir() || st.Mode().Perm() != 0700 {
		t.Fatal(st, e)
	}
	if e = privateDirectory(base); e != nil {
		t.Fatal("reinstall", e)
	}
}
func TestPrivateDirectoryRejectsManagedAliasWithoutChangingTarget(t *testing.T) {
	root := t.TempDir()
	outside := filepath.Join(root, "outside")
	os.Mkdir(outside, 0755)
	base := filepath.Join(root, "alicebridge-agent")
	os.Symlink(outside, base)
	if e := privateDirectory(base); e == nil {
		t.Fatal("managed alias accepted")
	}
	st, _ := os.Stat(outside)
	if st.Mode().Perm() != 0755 {
		t.Fatal("unrelated directory permissions changed")
	}
}
func TestPrivateDirectoryRejectsFileAndBrokenParent(t *testing.T) {
	root := t.TempDir()
	base := filepath.Join(root, "agent")
	os.WriteFile(base, []byte("keep"), 0600)
	if e := privateDirectory(base); e == nil {
		t.Fatal("file accepted")
	}
	os.Symlink(filepath.Join(root, "absent"), filepath.Join(root, "alias"))
	if e := privateDirectory(filepath.Join(root, "alias", "agent")); e == nil {
		t.Fatal("broken parent accepted")
	}
}
