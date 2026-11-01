package guardian

import "testing"

func TestRuntimeAndroidHasNoBootModification(t *testing.T) {
	h, e := hook("android")
	if e != nil || !h.Runtime || h.Path != "" {
		t.Fatal(h, e)
	}
	data, e := StartupText(h, nil)
	if e != nil || len(data) != 0 {
		t.Fatal("runtime mode must not write a startup file", e)
	}
	restore, e := mountWrite("android")
	if e != nil {
		t.Fatal(e)
	}
	if e = restore(); e != nil {
		t.Fatal(e)
	}
}
