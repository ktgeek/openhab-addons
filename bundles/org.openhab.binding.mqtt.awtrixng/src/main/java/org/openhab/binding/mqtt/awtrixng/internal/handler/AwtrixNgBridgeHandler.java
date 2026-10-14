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
package org.openhab.binding.mqtt.awtrixng.internal.handler;

import static org.openhab.binding.mqtt.awtrixng.internal.AwtrixNgBindingConstants.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.measure.Quantity;
import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mqtt.awtrixng.internal.BridgeConfigOptions;
import org.openhab.binding.mqtt.awtrixng.internal.Helper;
import org.openhab.binding.mqtt.awtrixng.internal.PayloadChannel;
import org.openhab.binding.mqtt.awtrixng.internal.ScreenImage;
import org.openhab.binding.mqtt.awtrixng.internal.SettingsChannels;
import org.openhab.binding.mqtt.awtrixng.internal.dto.DeviceState;
import org.openhab.binding.mqtt.awtrixng.internal.dto.Indicator;
import org.openhab.binding.mqtt.awtrixng.internal.dto.IndicatorCommand;
import org.openhab.binding.mqtt.awtrixng.internal.dto.ScreenFrame;
import org.openhab.binding.mqtt.awtrixng.internal.dto.Settings;
import org.openhab.binding.mqtt.awtrixng.internal.dto.SoundSpec;
import org.openhab.binding.mqtt.handler.AbstractBrokerHandler;
import org.openhab.core.io.transport.mqtt.MqttBrokerConnection;
import org.openhab.core.io.transport.mqtt.MqttMessageSubscriber;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.HSBType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.MetricPrefix;
import org.openhab.core.library.unit.SIUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link AwtrixNgBridgeHandler} represents an AWTRIX NG clock. It follows the retained state topics of the clock,
 * maps them to channels and publishes the commands the user sends to them.
 *
 * @author Keith T. Garner - Initial contribution
 */
@NonNullByDefault
public class AwtrixNgBridgeHandler extends BaseBridgeHandler implements MqttMessageSubscriber {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(THING_TYPE_CLOCK);

    private static final int MQTT_QOS = 1;
    private static final String DEFAULT_INDICATOR_COLOR = "#00FF00";
    private static final String NO_INDICATOR_PAYLOAD = "{}";
    private static final String MOODLIGHT_ON_COLOR = "#FFFFFF";

    private final Logger logger = LoggerFactory.getLogger(AwtrixNgBridgeHandler.class);

    private volatile @Nullable MqttBrokerConnection connection;
    private volatile String basetopic = "";
    private volatile int lowBatteryThreshold = 25;
    // True between initialize() and dispose(), so late callbacks of the broker do not touch a disposed handler
    private volatile boolean active;
    private volatile boolean deviceOnline;

    public AwtrixNgBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        BridgeConfigOptions config = getConfigAs(BridgeConfigOptions.class);
        String base = config.basetopic.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.isEmpty()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "The base topic is not set");
            return;
        }
        this.basetopic = base;
        this.lowBatteryThreshold = config.lowBatteryThreshold;
        this.active = true;
        bridgeStatusChanged(getBridgeStatus());
    }

    @Override
    public void dispose() {
        this.active = false;
        this.deviceOnline = false;
        MqttBrokerConnection localConnection = connection;
        if (localConnection != null) {
            unsubscribeAll(localConnection);
        }
        this.connection = null;
    }

    @Override
    public void bridgeStatusChanged(ThingStatusInfo bridgeStatusInfo) {
        if (!active) {
            return;
        }
        if (bridgeStatusInfo.getStatus() == ThingStatus.OFFLINE) {
            // The retained availability message is delivered again once the broker is back
            this.deviceOnline = false;
            this.connection = null;
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE);
            return;
        }
        if (bridgeStatusInfo.getStatus() != ThingStatus.ONLINE) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR);
            return;
        }

        Bridge broker = getBridge();
        ThingHandler handler = broker == null ? null : broker.getHandler();
        if (handler instanceof AbstractBrokerHandler brokerHandler) {
            brokerHandler.getConnectionAsync().thenAccept(this::connectionAvailable).exceptionally(e -> {
                if (active) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                            "The MQTT broker has no valid connection");
                }
                return null;
            });
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_UNINITIALIZED,
                    "The MQTT broker is missing, set up a working broker first");
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String channelId = channelUID.getId();
        if (command instanceof RefreshType) {
            if (CHANNEL_SCREEN.equals(channelId)) {
                publish(TOPIC_CMD_SCREEN_GET, "");
            }
            return;
        }
        switch (channelId) {
            case CHANNEL_AUTO_BRIGHTNESS -> {
                if (command instanceof OnOffType onOff) {
                    Settings settings = new Settings();
                    settings.autoBrightness = onOff == OnOffType.ON;
                    publish(TOPIC_CMD_SETTINGS, Helper.toJson(settings));
                }
            }
            case CHANNEL_BRIGHTNESS -> handleBrightness(command);
            case CHANNEL_DISPLAY -> {
                if (command instanceof OnOffType onOff) {
                    publish(TOPIC_CMD_DISPLAY, Helper.toJson(Map.of("power", onOff == OnOffType.ON)));
                }
            }
            case CHANNEL_APP -> {
                if (command instanceof StringType) {
                    publish(TOPIC_CMD_APPS_SWITCH, command.toString());
                }
            }
            case CHANNEL_SOUND -> {
                if (command instanceof StringType) {
                    SoundSpec sound = new SoundSpec();
                    sound.file = command.toString();
                    publish(TOPIC_CMD_AUDIO_PLAY, Helper.toJson(sound));
                }
            }
            case CHANNEL_RTTTL -> {
                if (command instanceof StringType) {
                    SoundSpec sound = new SoundSpec();
                    sound.rtttl = command.toString();
                    publish(TOPIC_CMD_AUDIO_PLAY, Helper.toJson(sound));
                }
            }
            case CHANNEL_MOODLIGHT -> handleMoodlight(command);
            case CHANNEL_OVERLAY -> {
                if (command instanceof StringType) {
                    String overlay = command.toString().trim();
                    publish(TOPIC_CMD_DISPLAY,
                            overlay.isEmpty() ? "{\"overlay\":null}" : Helper.toJson(Map.of("overlay", overlay)));
                }
            }
            default -> {
                PayloadChannel<Settings> setting = SettingsChannels.get(channelId);
                int indicator = indicatorNumber(channelId);
                if (setting != null) {
                    handleSetting(setting, command);
                } else if (indicator > 0) {
                    handleIndicator(indicator, command);
                }
            }
        }
    }

    @Override
    public void processMessage(String topic, byte[] payload) {
        String base = basetopic;
        if (!topic.startsWith(base)) {
            return;
        }
        String message = new String(payload, StandardCharsets.UTF_8);
        switch (topic.substring(base.length())) {
            case TOPIC_AVAILABILITY -> handleAvailability(message);
            case TOPIC_STATE_DEVICE -> handleDeviceState(message);
            case TOPIC_STATE_SETTINGS -> handleSettings(message);
            case TOPIC_STATE_ACTIVE_APP -> handleActiveApp(message);
            case TOPIC_STATE_SCREEN -> handleScreen(message);
            case TOPIC_STATE_BUTTON_LEFT -> handleButton(CHANNEL_BUTTON_LEFT, message);
            case TOPIC_STATE_BUTTON_SELECT -> handleButton(CHANNEL_BUTTON_SELECT, message);
            case TOPIC_STATE_BUTTON_RIGHT -> handleButton(CHANNEL_BUTTON_RIGHT, message);
            case TOPIC_EVENT_ERROR -> handleError(message);
            default -> logger.trace("Ignoring message on topic {}", topic);
        }
    }

    private ThingStatusInfo getBridgeStatus() {
        Bridge broker = getBridge();
        return broker != null ? broker.getStatusInfo()
                : new ThingStatusInfo(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, null);
    }

    private void connectionAvailable(MqttBrokerConnection newConnection) {
        if (!active) {
            return;
        }
        if (!deviceOnline) {
            // Before subscribing, as the retained availability message may arrive immediately
            updateStatus(ThingStatus.UNKNOWN, ThingStatusDetail.NONE, "Waiting for the clock to report availability");
        }
        this.connection = newConnection;
        String base = basetopic;
        newConnection.subscribe(base + TOPIC_AVAILABILITY, this);
        newConnection.subscribe(base + TOPIC_STATE + "/#", this);
        newConnection.subscribe(base + TOPIC_EVENT_ERROR, this);
    }

    private void unsubscribeAll(MqttBrokerConnection oldConnection) {
        String base = basetopic;
        oldConnection.unsubscribe(base + TOPIC_AVAILABILITY, this);
        oldConnection.unsubscribe(base + TOPIC_STATE + "/#", this);
        oldConnection.unsubscribe(base + TOPIC_EVENT_ERROR, this);
    }

    private void publish(String topicSuffix, String payload) {
        MqttBrokerConnection localConnection = connection;
        if (localConnection == null) {
            logger.debug("Not connected to the broker, dropping the message for {}", topicSuffix);
            return;
        }
        String topic = basetopic + topicSuffix;
        logger.debug("Publishing {} to {}", payload, topic);
        localConnection.publish(topic, payload.getBytes(StandardCharsets.UTF_8), MQTT_QOS, false);
    }

    private void handleBrightness(Command command) {
        Double percent = Helper.toPercent(command);
        if (percent == null || percent < 0 || percent > 100) {
            logger.debug("Ignoring brightness command {}", command);
            return;
        }
        Settings settings = new Settings();
        settings.autoBrightness = false;
        settings.brightness = (int) Math.round(255 * percent / 100);
        publish(TOPIC_CMD_SETTINGS, Helper.toJson(settings));
        updateChannel(CHANNEL_AUTO_BRIGHTNESS, OnOffType.OFF);
    }

    private static int indicatorNumber(String channelId) {
        if (channelId.startsWith(CHANNEL_INDICATOR_PREFIX)) {
            try {
                int number = Integer.parseInt(channelId.substring(CHANNEL_INDICATOR_PREFIX.length()));
                return number >= 1 && number <= INDICATOR_COUNT ? number : 0;
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private void handleSetting(PayloadChannel<Settings> setting, Command command) {
        Settings update = setting.toPayload(Settings::new, command);
        if (update == null) {
            logger.debug("Ignoring command {} that does not fit the setting", command);
            return;
        }
        publish(TOPIC_CMD_SETTINGS, Helper.toJson(update));
    }

    /** Switching the mood light off takes an empty payload, a color switches it on. */
    private void handleMoodlight(Command command) {
        if (command instanceof HSBType color) {
            publish(TOPIC_CMD_MOODLIGHT,
                    "#000000".equals(Helper.toHex(color)) ? "" : Helper.toJson(Map.of("color", Helper.toHex(color))));
        } else if (command == OnOffType.ON) {
            publish(TOPIC_CMD_MOODLIGHT, Helper.toJson(Map.of("color", MOODLIGHT_ON_COLOR)));
        } else if (command == OnOffType.OFF) {
            publish(TOPIC_CMD_MOODLIGHT, "");
        } else {
            logger.debug("Ignoring mood light command {}", command);
        }
    }

    private void handleIndicator(int indicator, Command command) {
        String payload = NO_INDICATOR_PAYLOAD;
        if (command instanceof HSBType color) {
            String hex = Helper.toHex(color);
            payload = "#000000".equals(hex) ? NO_INDICATOR_PAYLOAD : Helper.toJson(new IndicatorCommand(hex));
        } else if (command == OnOffType.ON) {
            payload = Helper.toJson(new IndicatorCommand(DEFAULT_INDICATOR_COLOR));
        } else if (command != OnOffType.OFF) {
            logger.debug("Ignoring indicator command {}", command);
            return;
        }
        publish(TOPIC_CMD_INDICATOR + indicator, payload);
    }

    private void handleAvailability(String message) {
        boolean online = AVAILABILITY_ONLINE.equals(message.trim());
        this.deviceOnline = online;
        if (online) {
            updateStatus(ThingStatus.ONLINE);
        } else {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "The clock reported offline");
        }
    }

    private void handleDeviceState(String message) {
        DeviceState state = Helper.fromJson(message, DeviceState.class);
        if (state == null) {
            logger.debug("Ignoring invalid device state: {}", message);
            return;
        }
        updateProperties(state);

        if (state.matrixPower != null) {
            updateChannel(CHANNEL_DISPLAY, OnOffType.from(state.matrixPower));
        }
        if (state.brightness != null) {
            updateChannel(CHANNEL_BRIGHTNESS,
                    new QuantityType<>(Math.round(state.brightness * 100 / 255.0), Units.PERCENT));
        }
        updateQuantity(CHANNEL_LIGHT_LEVEL, state.lightLevel, Units.PERCENT);
        updateQuantity(CHANNEL_TEMPERATURE, state.temperature, SIUnits.CELSIUS);
        updateQuantity(CHANNEL_HUMIDITY, state.humidity, Units.PERCENT);
        updateQuantity(CHANNEL_PRESSURE, state.pressureHpa, MetricPrefix.HECTO(SIUnits.PASCAL));
        updateQuantity(CHANNEL_BATTERY_LEVEL, state.batteryPercent, Units.PERCENT);
        updateQuantity(CHANNEL_BATTERY_VOLTAGE, state.batteryVoltage, Units.VOLT);
        updateQuantity(CHANNEL_RSSI, state.wifiRssi, Units.DECIBEL_MILLIWATTS);
        updateQuantity(CHANNEL_UPTIME, state.uptimeSeconds, Units.SECOND);
        updateQuantity(CHANNEL_FREE_HEAP, state.freeHeapBytes, Units.BYTE);
        if (state.fps != null) {
            updateChannel(CHANNEL_FPS, new DecimalType(state.fps));
        }
        if (state.ipAddress != null) {
            updateChannel(CHANNEL_IP_ADDRESS, new StringType(state.ipAddress));
        }
        if (state.lowBattery != null) {
            updateChannel(CHANNEL_LOW_BATTERY, OnOffType.from(state.lowBattery));
        } else if (state.batteryPercent != null) {
            updateChannel(CHANNEL_LOW_BATTERY, OnOffType.from(state.batteryPercent < lowBatteryThreshold));
        }
        updateIndicators(state.indicators);
    }

    private void updateProperties(DeviceState state) {
        Map<String, String> reported = new HashMap<>();
        putIfPresent(reported, PROP_FIRMWARE, state.version);
        putIfPresent(reported, PROP_UNIQUEID, state.uid);
        putIfPresent(reported, PROP_BOARD_TYPE, state.boardType);
        putIfPresent(reported, PROP_MAC_ADDRESS, state.macAddress);
        putIfPresent(reported, PROP_HOSTNAME, state.hostname);
        reported.entrySet().removeIf(e -> e.getValue().equals(thing.getProperties().get(e.getKey())));
        if (!reported.isEmpty()) {
            updateProperties(reported);
        }
    }

    private static void putIfPresent(Map<String, String> properties, String name, @Nullable String value) {
        if (value != null) {
            properties.put(name, value);
        }
    }

    private void updateIndicators(@Nullable List<Indicator> indicators) {
        if (indicators == null) {
            return;
        }
        for (int i = 0; i < Math.min(indicators.size(), INDICATOR_COUNT); i++) {
            Indicator indicator = indicators.get(i);
            HSBType color = indicator.on ? Helper.fromHex(indicator.color) : null;
            updateChannel(CHANNEL_INDICATOR_PREFIX + (i + 1), color != null ? color : HSBType.BLACK);
        }
    }

    private void handleSettings(String message) {
        Settings settings = Helper.fromJson(message, Settings.class);
        if (settings == null) {
            logger.debug("Ignoring invalid settings: {}", message);
            return;
        }
        if (settings.autoBrightness != null) {
            updateChannel(CHANNEL_AUTO_BRIGHTNESS, OnOffType.from(settings.autoBrightness));
        }
        for (Map.Entry<String, PayloadChannel<Settings>> channel : SettingsChannels.all()) {
            State state = channel.getValue().toState(settings);
            if (state != null) {
                updateChannel(channel.getKey(), state);
            }
        }
    }

    private void handleActiveApp(String message) {
        updateChannel(CHANNEL_APP, new StringType(message));
        if (isLinked(new ChannelUID(thing.getUID(), CHANNEL_SCREEN))) {
            publish(TOPIC_CMD_SCREEN_GET, "");
        }
    }

    private void handleScreen(String message) {
        ScreenFrame frame = Helper.fromJson(message, ScreenFrame.class);
        if (frame == null) {
            logger.debug("Ignoring invalid screen frame");
            return;
        }
        try {
            byte[] png = ScreenImage.toPng(frame);
            if (png.length > 0) {
                updateChannel(CHANNEL_SCREEN, new RawType(png, "image/png"));
            } else {
                logger.debug("Ignoring screen frame of {}x{} with {} pixels", frame.width, frame.height,
                        frame.pixels.length);
            }
        } catch (IOException e) {
            logger.warn("Failed to render the screen image", e);
        }
    }

    private void handleButton(String channelId, String message) {
        triggerChannel(new ChannelUID(thing.getUID(), channelId),
                BUTTON_PRESSED_PAYLOAD.equals(message.trim()) ? EVENT_PRESSED : EVENT_RELEASED);
    }

    private void handleError(String message) {
        logger.debug("The clock reported an error: {}", message);
        triggerChannel(new ChannelUID(thing.getUID(), CHANNEL_ERROR), message);
    }

    private <Q extends Quantity<Q>> void updateQuantity(String channelId, @Nullable Number value, Unit<Q> unit) {
        if (value != null) {
            updateChannel(channelId, new QuantityType<>(value, unit));
        }
    }

    private void updateChannel(String channelId, State state) {
        updateState(new ChannelUID(thing.getUID(), channelId), state);
    }
}
