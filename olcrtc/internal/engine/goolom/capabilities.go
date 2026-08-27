package goolom

// goolomCapabilitiesOffer is the feature matrix this client advertises in its
// hello. The SFU picks one mode per capability out of the offered lists; the
// literal mirrors what the reference web SDK sends.
func goolomCapabilitiesOffer() map[string]any {
	return map[string]any{
		"offerAnswerMode":        []string{"SEPARATE"},
		"initialSubscriberOffer": []string{"ON_HELLO"},
		"slotsMode":              []string{"FROM_CONTROLLER"},
		"simulcastMode":          []string{"DISABLED", "STATIC"},
		"selfVadStatus":          []string{"FROM_SERVER", "FROM_CLIENT"},
		"dataChannelSharing":     []string{"TO_RTP"},
		"videoEncoderConfig":     []string{"NO_CONFIG", "ONLY_INIT_CONFIG", "RUNTIME_CONFIG"},
		"dataChannelVideoCodec":  []string{"VP8", "UNIQUE_CODEC_FROM_TRACK_DESCRIPTION"},
		"bandwidthLimitationReason": []string{
			"BANDWIDTH_REASON_DISABLED",
			"BANDWIDTH_REASON_ENABLED",
		},
		"sdkDefaultDeviceManagement": []string{
			"SDK_DEFAULT_DEVICE_MANAGEMENT_DISABLED",
			"SDK_DEFAULT_DEVICE_MANAGEMENT_ENABLED",
		},
		"joinOrderLayout": []string{"JOIN_ORDER_LAYOUT_DISABLED", "JOIN_ORDER_LAYOUT_ENABLED"},
		"pinLayout":       []string{"PIN_LAYOUT_DISABLED"},
		"sendSelfViewVideoSlot": []string{
			"SEND_SELF_VIEW_VIDEO_SLOT_DISABLED",
			"SEND_SELF_VIEW_VIDEO_SLOT_ENABLED",
		},
		"serverLayoutTransition": []string{"SERVER_LAYOUT_TRANSITION_DISABLED"},
		"sdkPublisherOptimizeBitrate": []string{
			"SDK_PUBLISHER_OPTIMIZE_BITRATE_DISABLED",
			"SDK_PUBLISHER_OPTIMIZE_BITRATE_FULL",
			"SDK_PUBLISHER_OPTIMIZE_BITRATE_ONLY_SELF",
		},
		"sdkNetworkLostDetection": []string{"SDK_NETWORK_LOST_DETECTION_DISABLED"},
		"sdkNetworkPathMonitor":   []string{"SDK_NETWORK_PATH_MONITOR_DISABLED"},
		"publisherVp9":            []string{"PUBLISH_VP9_DISABLED", "PUBLISH_VP9_ENABLED"},
		"svcMode":                 []string{"SVC_MODE_DISABLED", "SVC_MODE_L3T3", "SVC_MODE_L3T3_KEY"},
		"subscriberOfferAsyncAck": []string{"SUBSCRIBER_OFFER_ASYNC_ACK_DISABLED", "SUBSCRIBER_OFFER_ASYNC_ACK_ENABLED"},
		"androidBluetoothRoutingFix": []string{
			"ANDROID_BLUETOOTH_ROUTING_FIX_DISABLED",
		},
		"fixedIceCandidatesPoolSize": []string{
			"FIXED_ICE_CANDIDATES_POOL_SIZE_DISABLED",
		},
		"sdkAndroidTelecomIntegration": []string{
			"SDK_ANDROID_TELECOM_INTEGRATION_DISABLED",
		},
		"setActiveCodecsMode": []string{
			"SET_ACTIVE_CODECS_MODE_DISABLED",
			"SET_ACTIVE_CODECS_MODE_VIDEO_ONLY",
		},
		"subscriberDtlsPassiveMode": []string{
			"SUBSCRIBER_DTLS_PASSIVE_MODE_DISABLED",
		},
		"publisherOpusDred": []string{
			"PUBLISHER_OPUS_DRED_DISABLED",
		},
		"publisherOpusLowBitrate": []string{
			"PUBLISHER_OPUS_LOW_BITRATE_DISABLED",
		},
		"sdkAndroidDestroySessionOnTaskRemoved": []string{
			"SDK_ANDROID_DESTROY_SESSION_ON_TASK_REMOVED_DISABLED",
		},
		"svcModes":                []string{"FALSE"},
		"reportTelemetryModes":    []string{"TRUE"},
		"keepDefaultDevicesModes": []string{"FALSE"},
	}
}
