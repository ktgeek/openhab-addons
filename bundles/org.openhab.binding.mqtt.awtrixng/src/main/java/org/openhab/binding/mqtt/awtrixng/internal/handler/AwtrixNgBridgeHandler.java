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
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.measure.Quantity;
import javax.measure.Unit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mqtt.awtrixng.internal.AwtrixNgStateDescriptionProvider;
import org.openhab.binding.mqtt.awtrixng.internal.BridgeConfigOptions;
import org.openhab.binding.mqtt.awtrixng.internal.Helper;
import org.openhab.binding.mqtt.awtrixng.internal.PayloadChannel;
import org.openhab.binding.mqtt.awtrixng.internal.ScreenImage;
import org.openhab.binding.mqtt.awtrixng.internal.SettingsChannels;
import org.openhab.binding.mqtt.awtrixng.internal.action.AwtrixNgActions;
import org.openhab.binding.mqtt.awtrixng.internal.discovery.AwtrixNgBridgeDiscoveryService;
import org.openhab.binding.mqtt.awtrixng.internal.dto.Capabilities;
import org.openhab.binding.mqtt.awtrixng.internal.dto.DeviceState;
import org.openhab.binding.mqtt.awtrixng.internal.dto.Indicator;
import org.openhab.binding.mqtt.awtrixng.internal.dto.IndicatorCommand;
import org.openhab.binding.mqtt.awtrixng.internal.dto.NotificationPayload;
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
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerService;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.openhab.core.types.StateOption;
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
    private static final List<String> APP_CAPABILITY_CHANNELS = List.of(CHANNEL_EFFECT, CHANNEL_OVERLAY, CHANNEL_FONT);
    private static final String DEFAULT_INDICATOR_COLOR = "#00FF00";
    private static final String NO_INDICATOR_PAYLOAD = "{}";
    private static final String MOODLIGHT_ON_COLOR = "#FFFFFF";

    private final Logger logger = LoggerFactory.getLogger(AwtrixNgBridgeHandler.class);

    private volatile @Nullable MqttBrokerConnection connection;
    private volatile String basetopic = "";
    private volatile int lowBatteryThreshold = 25;
    private volatile int appLockTimeout = 10;
    private volatile boolean discoverDefaultApps;
    // True between initialize() and dispose(), so late callbacks of the broker do not touch a disposed handler
    private volatile boolean active;
    private volatile boolean deviceOnline;

    private final AwtrixNgStateDescriptionProvider stateDescriptionProvider;
    private final Map<String, AwtrixNgAppHandler> appHandlers = new ConcurrentHashMap<>();
    private volatile @Nullable Capabilities capabilities;
    private volatile @Nullable AwtrixNgBridgeDiscoveryService discoveryCallback;
    private volatile String currentApp = "";

    // The clock navigates by itself while no app is locked, so the settings it reports then are the ones to go back to
    private volatile @Nullable Boolean reportedAutoTransition;
    private volatile @Nullable Boolean reportedBlockNavigation;

    // Guards the app lock; it is never held while calling out
    private final Object appLockMonitor = new Object();
    private boolean appLock;
    private boolean autoTransitionToRestore = true;
    private boolean blockNavigationToRestore;
    private @Nullable ScheduledFuture<?> appLockTimeoutJob;
    private @Nullable ScheduledFuture<?> appLockWarningJob;

    public AwtrixNgBridgeHandler(Bridge bridge, AwtrixNgStateDescriptionProvider stateDescriptionProvider) {
        super(bridge);
        this.stateDescriptionProvider = stateDescriptionProvider;
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
        this.appLockTimeout = config.appLockTimeout;
        this.discoverDefaultApps = config.discoverDefaultApps;
        this.active = true;
        bridgeStatusChanged(getBridgeStatus());
    }

    @Override
    public void dispose() {
        leaveAppControlMode();
        stateDescriptionProvider.removeStateOptions(new ChannelUID(thing.getUID(), CHANNEL_TRANSITION_EFFECT));
        stateDescriptionProvider.removeStateOptions(new ChannelUID(thing.getUID(), CHANNEL_OVERLAY));
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
                    play(sound);
                }
            }
            case CHANNEL_RTTTL -> {
                if (command instanceof StringType) {
                    SoundSpec sound = new SoundSpec();
                    sound.rtttl = command.toString();
                    play(sound);
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
            case TOPIC_STATE_CAPABILITIES -> handleCapabilities(message);
            case TOPIC_STATE_ACTIVE_APP -> handleActiveApp(message);
            case TOPIC_STATE_SCREEN -> handleScreen(message);
            case TOPIC_STATE_BUTTON_LEFT -> handleButton(CHANNEL_BUTTON_LEFT, message);
            case TOPIC_STATE_BUTTON_SELECT -> handleButton(CHANNEL_BUTTON_SELECT, message);
            case TOPIC_STATE_BUTTON_RIGHT -> handleButton(CHANNEL_BUTTON_RIGHT, message);
            case TOPIC_EVENT_ERROR -> handleError(message);
            default -> logger.trace("Ignoring message on topic {}", topic);
        }
    }

    @Override
    public void childHandlerInitialized(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof AwtrixNgAppHandler app && APP_NAME_PATTERN.matcher(app.getAppName()).matches()) {
            appHandlers.put(app.getAppName(), app);
            Capabilities known = capabilities;
            if (known != null) {
                applyAppCapabilities(childThing.getUID(), known);
            }
            MqttBrokerConnection localConnection = connection;
            if (localConnection != null) {
                localConnection.subscribe(basetopic + TOPIC_STORE_APPS + app.getAppName(), app);
            }
        }
    }

    @Override
    public void childHandlerDisposed(ThingHandler childHandler, Thing childThing) {
        if (childHandler instanceof AwtrixNgAppHandler app && appHandlers.remove(app.getAppName(), app)) {
            for (String channelId : APP_CAPABILITY_CHANNELS) {
                stateDescriptionProvider.removeStateOptions(new ChannelUID(childThing.getUID(), channelId));
            }
            MqttBrokerConnection localConnection = connection;
            if (localConnection != null) {
                localConnection.unsubscribe(basetopic + TOPIC_STORE_APPS + app.getAppName(), app);
            }
        }
    }

    @Override
    public Collection<Class<? extends ThingHandlerService>> getServices() {
        return List.of(AwtrixNgBridgeDiscoveryService.class, AwtrixNgActions.class);
    }

    public void setAppDiscoveryCallback(AwtrixNgBridgeDiscoveryService discovery) {
        this.discoveryCallback = discovery;
    }

    public void removeAppDiscoveryCallback() {
        this.discoveryCallback = null;
    }

    // The operations below are those of the rule actions. They check what they are given, as a rule can pass anything,
    // and answer to the log: NG rejects a command as a whole and only tells its error topic.

    /** Switches an indicator on, with its color blinking or fading. */
    public void setIndicator(int indicator, String color, @Nullable Integer blinkMs, @Nullable Integer fadeMs) {
        String hex = Helper.normalizeColor(color);
        if (!isIndicator(indicator) || hex == null || !isIndicatorTime(blinkMs) || !isIndicatorTime(fadeMs)) {
            logger.warn("Ignoring indicator {} with color {}, blink {} and fade {}: the indicator is 1 to 3, the color"
                    + " #RRGGBB and the times 0 to {} ms", indicator, color, blinkMs, fadeMs, INDICATOR_MAX_MS);
            return;
        }
        IndicatorCommand command = new IndicatorCommand(hex);
        command.blinkMs = blinkMs;
        command.fadeMs = fadeMs;
        publish(TOPIC_CMD_INDICATOR + indicator, Helper.toJson(command));
    }

    public void resetIndicator(int indicator) {
        if (isIndicator(indicator)) {
            publish(TOPIC_CMD_INDICATOR + indicator, NO_INDICATOR_PAYLOAD);
        } else {
            logger.warn("Ignoring indicator {}, it is 1 to 3", indicator);
        }
    }

    public void setMoodlight(String color, @Nullable Integer brightness) {
        String hex = Helper.normalizeColor(color);
        if (hex == null || (brightness != null && (brightness < 0 || brightness > 255))) {
            logger.warn("Ignoring mood light with color {} and brightness {}: the color is #RRGGBB, the brightness"
                    + " 0 to 255", color, brightness);
            return;
        }
        publish(TOPIC_CMD_MOODLIGHT, Helper.jsonObject("color", hex, "brightness", brightness));
    }

    /** Switches the mood light off, which an empty payload does. */
    public void clearMoodlight() {
        publish(TOPIC_CMD_MOODLIGHT, "");
    }

    public void reboot() {
        publish(TOPIC_CMD_DEVICE_REBOOT, "");
    }

    public void sleep(int durationMs) {
        if (durationMs <= 0) {
            logger.warn("Ignoring sleep of {} ms, it must be more than 0", durationMs);
            return;
        }
        publish(TOPIC_CMD_DEVICE_SLEEP, Helper.jsonObject("durationMs", durationMs));
    }

    public void play(SoundSpec sound) {
        publish(TOPIC_CMD_AUDIO_PLAY, Helper.toJson(sound));
    }

    /** @param group alert, app or radio, or null to stop all sounds */
    public void stopSound(@Nullable String group) {
        if (group == null || group.isBlank()) {
            publish(TOPIC_CMD_AUDIO_STOP, NO_INDICATOR_PAYLOAD);
        } else if (SOUND_GROUPS.contains(group)) {
            publish(TOPIC_CMD_AUDIO_STOP, Helper.jsonObject("group", group));
        } else {
            logger.warn("Ignoring sound group {}, it is one of {}", group, SOUND_GROUPS);
        }
    }

    public void showNotification(NotificationPayload notification) {
        publish(TOPIC_CMD_NOTIFY, Helper.toJson(notification));
    }

    /** @param name the name the notification was shown with, or null for the notification on the display */
    public void dismissNotification(@Nullable String name) {
        if (name == null || name.isBlank()) {
            publish(TOPIC_CMD_NOTIFY_DISMISS, "");
        } else if (APP_NAME_PATTERN.matcher(name).matches()) {
            publish(TOPIC_CMD_NOTIFY_DISMISS + "/" + name, "");
        } else {
            logger.warn("Ignoring notification name {}, it has 1 to 32 letters, digits, - or _", name);
        }
    }

    public void nextApp() {
        publish(TOPIC_CMD_APPS_NEXT, "");
    }

    public void previousApp() {
        publish(TOPIC_CMD_APPS_PREVIOUS, "");
    }

    public void switchApp(String appName, boolean fast) {
        if (appName.isBlank()) {
            logger.warn("Ignoring switch to an app without a name");
            return;
        }
        publish(TOPIC_CMD_APPS_SWITCH, Helper.jsonObject("name", appName, "fast", fast));
    }

    /**
     * @param order the apps in the order they rotate, null to keep the order
     * @param disabled all apps that are switched off, every other app is switched on
     */
    public void setAppOrder(@Nullable List<String> order, List<String> disabled) {
        if (!allAppNames(order) || !allAppNames(disabled)) {
            logger.warn("Ignoring app order with {} and {}: app names have 1 to 32 letters, digits, - or _", order,
                    disabled);
            return;
        }
        publish(TOPIC_CMD_APPS_ORDER,
                Helper.jsonObject("order", order == null || order.isEmpty() ? null : order, "disabled", disabled));
    }

    public void setAppEnabled(String appName, boolean enabled) {
        if (!APP_NAME_PATTERN.matcher(appName).matches()) {
            logger.warn("Ignoring app name {}, it has 1 to 32 letters, digits, - or _", appName);
            return;
        }
        publish(TOPIC_CMD_APPS + appName + "/enabled", Boolean.toString(enabled));
    }

    private static boolean isIndicator(int indicator) {
        return indicator >= 1 && indicator <= INDICATOR_COUNT;
    }

    private static boolean isIndicatorTime(@Nullable Integer millis) {
        return millis == null || (millis >= 0 && millis <= INDICATOR_MAX_MS);
    }

    private static boolean allAppNames(@Nullable List<String> names) {
        return names == null || names.stream().allMatch(name -> APP_NAME_PATTERN.matcher(name).matches());
    }

    /** Creates or updates the pushed app on the clock. */
    void pushApp(String appName, String payload) {
        publish(TOPIC_CMD_APPS_PUSHED + appName, payload);
    }

    /** Keeps the state of the app, to restore it after openHAB restarted. */
    void storeApp(String appName, String payload) {
        publish(TOPIC_STORE_APPS + appName, payload, true);
    }

    /** Removes the app from the clock and from the store. */
    void deleteApp(String appName) {
        publish(TOPIC_CMD_APPS_PUSHED + appName, "");
        publish(TOPIC_STORE_APPS + appName, "", true);
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
        appHandlers.forEach((name, app) -> newConnection.subscribe(base + TOPIC_STORE_APPS + name, app));
    }

    private void unsubscribeAll(MqttBrokerConnection oldConnection) {
        String base = basetopic;
        oldConnection.unsubscribe(base + TOPIC_AVAILABILITY, this);
        oldConnection.unsubscribe(base + TOPIC_STATE + "/#", this);
        oldConnection.unsubscribe(base + TOPIC_EVENT_ERROR, this);
        appHandlers.forEach((name, app) -> oldConnection.unsubscribe(base + TOPIC_STORE_APPS + name, app));
    }

    private void publish(String topicSuffix, String payload) {
        publish(topicSuffix, payload, false);
    }

    private void publish(String topicSuffix, String payload, boolean retain) {
        MqttBrokerConnection localConnection = connection;
        if (localConnection == null) {
            logger.debug("Not connected to the broker, dropping the message for {}", topicSuffix);
            return;
        }
        String topic = basetopic + topicSuffix;
        logger.debug("Publishing {} to {}", payload, topic);
        localConnection.publish(topic, payload.getBytes(StandardCharsets.UTF_8), MQTT_QOS, retain);
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
            String hex = Helper.toHex(color);
            if ("#000000".equals(hex)) {
                clearMoodlight();
            } else {
                setMoodlight(hex, null);
            }
        } else if (command == OnOffType.ON) {
            setMoodlight(MOODLIGHT_ON_COLOR, null);
        } else if (command == OnOffType.OFF) {
            clearMoodlight();
        } else {
            logger.debug("Ignoring mood light command {}", command);
        }
    }

    private void handleIndicator(int indicator, Command command) {
        if (command instanceof HSBType color) {
            String hex = Helper.toHex(color);
            if ("#000000".equals(hex)) {
                resetIndicator(indicator);
            } else {
                setIndicator(indicator, hex, null, null);
            }
        } else if (command == OnOffType.ON) {
            setIndicator(indicator, DEFAULT_INDICATOR_COLOR, null, null);
        } else if (command == OnOffType.OFF) {
            resetIndicator(indicator);
        } else {
            logger.debug("Ignoring indicator command {}", command);
        }
    }

    private void handleAvailability(String message) {
        boolean online = AVAILABILITY_ONLINE.equals(message.trim());
        boolean wasOnline = this.deviceOnline;
        this.deviceOnline = online;
        if (online) {
            updateStatus(ThingStatus.ONLINE);
            if (!wasOnline) {
                // Pushed apps do not survive a reboot of the clock, so they are sent again
                appHandlers.values().forEach(AwtrixNgAppHandler::deviceOnline);
            }
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
        synchronized (appLockMonitor) {
            if (!appLock) {
                reportedAutoTransition = settings.autoTransition;
                reportedBlockNavigation = settings.blockNavigation;
            }
        }
        for (Map.Entry<String, PayloadChannel<Settings>> channel : SettingsChannels.all()) {
            State state = channel.getValue().toState(settings);
            if (state != null) {
                updateChannel(channel.getKey(), state);
            }
        }
    }

    private void handleActiveApp(String message) {
        currentApp = message;
        updateChannel(CHANNEL_APP, new StringType(message));
        appHandlers.forEach((name, app) -> app.setActive(name.equals(message)));
        AwtrixNgBridgeDiscoveryService discovery = discoveryCallback;
        if (discovery != null && !appHandlers.containsKey(message) && isDiscoverable(message)) {
            discovery.appDiscovered(message);
        }
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

    /**
     * The clock tells the names of its effects, transitions, overlays and fonts, which are the choices of the channels.
     */
    private void handleCapabilities(String message) {
        Capabilities reported = Helper.fromJson(message, Capabilities.class);
        if (reported == null) {
            logger.debug("Ignoring invalid capabilities: {}", message);
            return;
        }
        capabilities = reported;
        setOptions(new ChannelUID(thing.getUID(), CHANNEL_TRANSITION_EFFECT), reported.transitions);
        setOptions(new ChannelUID(thing.getUID(), CHANNEL_OVERLAY), reported.overlays);
        appHandlers.values().forEach(app -> applyAppCapabilities(app.getThing().getUID(), reported));
    }

    private void applyAppCapabilities(ThingUID appUid, Capabilities reported) {
        setOptions(new ChannelUID(appUid, CHANNEL_EFFECT), reported.effects);
        setOptions(new ChannelUID(appUid, CHANNEL_OVERLAY), reported.overlays);
        setOptions(new ChannelUID(appUid, CHANNEL_FONT),
                reported.fonts == null ? null : reported.fonts.stream().map(font -> font.name).toList());
    }

    /** A list that the clock did not report keeps the names of the thing type definition. */
    private void setOptions(ChannelUID channelUID, @Nullable List<String> names) {
        if (names != null && !names.isEmpty()) {
            stateDescriptionProvider.setStateOptions(channelUID,
                    names.stream().map(name -> new StateOption(name, name)).toList());
        }
    }

    private boolean isDiscoverable(String appName) {
        return APP_NAME_PATTERN.matcher(appName).matches() && !NOTIFICATION_APP.equals(appName)
                && (discoverDefaultApps || !BUILT_IN_APPS.contains(appName));
    }

    /**
     * The select button of an app that is controlled by the buttons locks the clock on that app, so that its own
     * buttons events can be used until the lock times out. While locked every button press also goes to the app.
     */
    private void handleButton(String channelId, String message) {
        String event = BUTTON_PRESSED_PAYLOAD.equals(message.trim()) ? EVENT_PRESSED : EVENT_RELEASED;
        triggerChannel(new ChannelUID(thing.getUID(), channelId), event);

        AwtrixNgAppHandler app = appHandlers.get(currentApp);
        if (app == null) {
            return;
        }
        boolean locked;
        synchronized (appLockMonitor) {
            locked = appLock;
        }
        if (locked) {
            scheduleAppLockTimeout();
            app.handleButton(channelId, event);
        } else if (CHANNEL_BUTTON_SELECT.equals(channelId) && EVENT_RELEASED.equals(event)
                && app.isButtonControlled()) {
            enterAppControlMode();
        }
    }

    private void enterAppControlMode() {
        synchronized (appLockMonitor) {
            if (appLock) {
                return;
            }
            appLock = true;
            // What the clock reported before the lock is what it goes back to, unknown means the defaults of NG
            Boolean autoTransition = reportedAutoTransition;
            Boolean blockNavigation = reportedBlockNavigation;
            autoTransitionToRestore = autoTransition == null || autoTransition;
            blockNavigationToRestore = blockNavigation != null && blockNavigation;
        }
        Settings lock = new Settings();
        lock.autoTransition = false;
        lock.blockNavigation = true;
        publish(TOPIC_CMD_SETTINGS, Helper.toJson(lock));
        scheduleAppLockTimeout();
    }

    /** Every press of a button extends the lock; it is also what shows the lock on the indicator. */
    private void scheduleAppLockTimeout() {
        synchronized (appLockMonitor) {
            if (!appLock) {
                return;
            }
            cancelAppLockJobs();
            appLockTimeoutJob = scheduler.schedule(this::leaveAppControlMode, appLockTimeout, TimeUnit.SECONDS);
            if (appLockTimeout > APP_LOCK_WARNING_SECONDS) {
                appLockWarningJob = scheduler.schedule(this::signalAppLockEnds,
                        appLockTimeout - APP_LOCK_WARNING_SECONDS, TimeUnit.SECONDS);
            }
        }
        setIndicator(APP_LOCK_INDICATOR, APP_LOCK_INDICATOR_COLOR, null, null);
    }

    private void signalAppLockEnds() {
        setIndicator(APP_LOCK_INDICATOR, APP_LOCK_INDICATOR_COLOR, null, APP_LOCK_WARNING_FADE_MS);
    }

    /** Package private so that tests need not wait for the timeout. */
    void leaveAppControlMode() {
        Settings restore = new Settings();
        synchronized (appLockMonitor) {
            if (!appLock) {
                return;
            }
            appLock = false;
            cancelAppLockJobs();
            restore.autoTransition = autoTransitionToRestore;
            restore.blockNavigation = blockNavigationToRestore;
        }
        resetIndicator(APP_LOCK_INDICATOR);
        publish(TOPIC_CMD_SETTINGS, Helper.toJson(restore));
    }

    /** Must be called with the app lock monitor held. */
    private void cancelAppLockJobs() {
        ScheduledFuture<?> timeout = appLockTimeoutJob;
        if (timeout != null) {
            timeout.cancel(false);
            appLockTimeoutJob = null;
        }
        ScheduledFuture<?> warning = appLockWarningJob;
        if (warning != null) {
            warning.cancel(false);
            appLockWarningJob = null;
        }
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
