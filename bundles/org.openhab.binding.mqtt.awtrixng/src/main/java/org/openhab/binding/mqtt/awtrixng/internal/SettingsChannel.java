/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mqtt.awtrixng.internal;

import java.util.function.BiPredicate;
import java.util.function.Function;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mqtt.awtrixng.internal.dto.Settings;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;

/**
 * A {@link SettingsChannel} links a channel to one field of the {@link Settings} of the clock: it reads the channel
 * state from the settings the clock reports, and puts a command into the partial settings sent back to it.
 *
 * @author Keith T. Garner - Initial contribution
 */
@NonNullByDefault
public class SettingsChannel {

    private final Function<Settings, @Nullable State> reader;
    private final BiPredicate<Settings, Command> writer;

    /**
     * @param reader returns the state for the settings of the clock, or null if the clock did not report the field
     * @param writer puts the command into the settings and returns true, or returns false if the command does not fit
     *            the channel
     */
    public SettingsChannel(Function<Settings, @Nullable State> reader, BiPredicate<Settings, Command> writer) {
        this.reader = reader;
        this.writer = writer;
    }

    public @Nullable State toState(Settings settings) {
        return reader.apply(settings);
    }

    /**
     * @return the settings update for the command, or null if the command does not fit the channel
     */
    public @Nullable Settings toSettings(Command command) {
        Settings update = new Settings();
        return writer.test(update, command) ? update : null;
    }
}
