package dev.openintel.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.openintel.gui.FeaturesScreen;

/** Opens the OpenIntel settings (Features) screen from Mod Menu's config button. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return FeaturesScreen::new;
    }
}
