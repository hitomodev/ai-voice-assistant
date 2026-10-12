package com.pilot.voiceassistant.tools;

import com.pilot.voiceassistant.hal.BoardControlHAL;
import com.pilot.voiceassistant.hal.BoardControlHAL.MediaCommand;

import java.util.Arrays;
import java.util.List;

/** The tools that control the board: volume, music and radio. */
public final class BoardTools {

    private static final String NOT_AVAILABLE = "Sorry, that is not available on this device.";

    private BoardTools() {
    }

    public static List<Tool> create(BoardControlHAL board) {
        return Arrays.asList(
                new Tool("volume_up",
                        "Increases the volume. Use when the user wants the sound louder or"
                                + " higher.",
                        () -> said(board.volumeUp(), "Volume up.")),
                new Tool("volume_down",
                        "Decreases the volume. Use when the user wants the sound quieter, lower"
                                + " or softer, or says it is too loud.",
                        () -> said(board.volumeDown(), "Volume down.")),
                new Tool("mute",
                        "Mutes the sound completely. Use for mute, silence or be quiet.",
                        () -> said(board.setMuted(true), "Muted.")),
                new Tool("unmute",
                        "Unmutes the sound. Use for unmute or turn the sound back on.",
                        () -> said(board.setMuted(false), "Sound on.")),
                new Tool("play_music",
                        "Plays or resumes the music.",
                        () -> said(board.media(MediaCommand.PLAY), "Playing music.")),
                new Tool("pause_music",
                        "Pauses or stops the music.",
                        () -> said(board.media(MediaCommand.PAUSE), "Music paused.")),
                new Tool("next_track",
                        "Goes to the next song or track.",
                        () -> said(board.media(MediaCommand.NEXT), "Next song.")),
                new Tool("previous_track",
                        "Goes back to the previous or last song or track.",
                        () -> said(board.media(MediaCommand.PREVIOUS), "Previous song.")),
                new Tool("radio_on",
                        "Turns the radio on.",
                        () -> said(board.setRadioOn(true), "Radio on.")),
                new Tool("radio_off",
                        "Turns the radio off.",
                        () -> said(board.setRadioOn(false), "Radio off.")),
                new Tool("get_current_time",
                        "Gets the current time. Use when the user asks for the time or the hour.",
                        () -> {
                            String time = board.currentTime();
                            return said(!time.isEmpty(), time);
                        }));
    }

    private static String said(boolean done, String confirmation) {
        return done ? confirmation : NOT_AVAILABLE;
    }
}
