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

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

/**
 * The {@link Helper} converts the JSON payloads exchanged with the clock. Gson omits null fields, which is what keeps a
 * payload free of keys AWTRIX NG would reject.
 *
 * @author Keith T. Garner - Initial contribution
 */
@NonNullByDefault
public class Helper {

    private static final Gson GSON = new GsonBuilder().create();

    private Helper() {
    }

    public static String toJson(Object payload) {
        return GSON.toJson(payload);
    }

    /**
     * Parses a payload received from the clock.
     *
     * @return the parsed payload, or null if it is empty or not valid JSON for the given type
     */
    public static <T> @Nullable T fromJson(String json, Class<T> type) {
        try {
            return GSON.fromJson(json, type);
        } catch (JsonParseException e) {
            return null;
        }
    }
}
