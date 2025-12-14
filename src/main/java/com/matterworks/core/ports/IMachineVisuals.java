package com.matterworks.core.ports;

/**
 * Astrazione per effetti client-side (Particelle, Animazioni).
 * Definita nel namespace Core_Ports.
 */
public interface IMachineVisuals {
    void playAnimation(String animationName);
    void spawnParticle(String particleId);
}