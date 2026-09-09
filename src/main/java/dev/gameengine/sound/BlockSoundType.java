package dev.gameengine.sound;

public interface BlockSoundType {

    float volume();

    float pitch();

    SoundEvent breakSound();

    SoundEvent hitSound();

    SoundEvent fallSound();

    SoundEvent placeSound();

    SoundEvent stepSound();
}
