package dev.openintel.investigate;

import dev.openintel.OpenIntelClient;
import net.minecraft.client.Camera;
import net.minecraft.client.CameraType;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

/** Applies one focus request before vanilla updates the camera and its culling/projection state. */
public final class InvestigateCamera {
    private static final InvestigateCameraMotion MOTION = new InvestigateCameraMotion();
    private static ClientLevel level;
    private static LocalPlayer player;
    private static CameraType perspective;

    private InvestigateCamera() { }

    public static void update(Camera camera, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (camera != mc.gameRenderer.getMainCamera()) return;
        CameraType type = mc.options.getCameraType();
        if (level != mc.level || player != mc.player || perspective != type) {
            MOTION.reset();
            level = mc.level;
            player = mc.player;
            perspective = type;
        }
        InvestigateSession.Focus focus = InvestigateSession.get().focus();
        var config = OpenIntelClient.config();
        if (config == null || player == null || level == null || !focus.watching() || focus.event() == null) {
            MOTION.reset();
            return;
        }
        if (mc.screen != null || mc.isPaused() || !mc.isWindowActive() || !mc.mouseHandler.isMouseGrabbed()
                || (camera.entity() != null && camera.entity() != player) || player.isSleeping()
                || !level.dimension().identifier().toString().equalsIgnoreCase(focus.event().dimension())) {
            MOTION.cancel();
            return;
        }
        var event = focus.event();
        var eye = player.getEyePosition(delta.getGameTimeDeltaPartialTick(true));
        boolean mirrored = type.isMirrored();
        float yaw = player.getYRot() + (mirrored ? 180 : 0);
        float pitch = mirrored ? -player.getXRot() : player.getXRot();
        var rotation = MOTION.update(focus.revision(), config.investigateCameraMode, config.investigatePanDurationMs,
                System.nanoTime(), yaw, pitch, event.x() + 0.5 - eye.x, event.y() + 0.5 - eye.y, event.z() + 0.5 - eye.z);
        if (rotation == null) return;
        float playerYaw = rotation.yaw() - (mirrored ? 180 : 0);
        float playerPitch = mirrored ? -rotation.pitch() : rotation.pitch();
        player.setYRot(playerYaw);
        player.setXRot(playerPitch);
        // Vanilla must use this frame's orientation directly, without re-interpolating a tick-old turn.
        player.yRotO = playerYaw;
        player.xRotO = playerPitch;
    }
}
