package dev.alicebridge.station;
import android.content.Context;
import android.content.ContextWrapper;
/** Explicit per-connection scope; never falls back to shared mutable application state. */
public final class StationContext extends ContextWrapper {
    final StationState state;
    StationContext(Context base, StationState state) { super(base.getApplicationContext()); this.state=state; }
    @Override public Context getApplicationContext() { return this; }
    static StationState require(Context context) {
        for(int depth=0;depth<16;depth++) {
            if(context instanceof StationContext)return ((StationContext)context).state;
            if(!(context instanceof ContextWrapper))break;
            context=((ContextWrapper)context).getBaseContext();
        }
        throw new IllegalArgumentException("A scoped station context is required");
    }
}
