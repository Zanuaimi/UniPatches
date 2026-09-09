package unipatch.overlaycore.modules;

import android.app.Activity;
import java.util.List;

/** Provider hook used by an app-specific overlay patch to register its target-specific modules. */
public interface OverlayAppSpecificModuleProvider {
    String profileId();
    List<OverlayAppSpecificModule> create(Activity activity);
}
