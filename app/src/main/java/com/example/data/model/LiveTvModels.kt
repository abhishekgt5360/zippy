package com.example.data.model

import com.squareup.moshi.Json

data class LiveTvChannel(
    val id: String,
    val name: String,
    val category: String = "Live TV",
    val logo: String? = null,
    val streamUrl: String,
    val countryCode: String? = null,
    val countryName: String? = null,
    val countryFlag: String? = null,
    val languageCodes: List<String> = emptyList()
) {
    val title: String get() = name
    val channelId: String get() = id
    val flagUrl: String? get() = logo
}

data class LiveTvCountry(
    @Json(name = "countryCode") val countryCode: String,
    @Json(name = "countryName") val countryName: String,
    @Json(name = "countryFlag") val countryFlag: String? = null,
    @Json(name = "countryFlagImage") val countryFlagImage: CountryFlagImage? = null
) {
    val flagEmoji: String
        get() = countryFlag?.takeIf { it.isNotBlank() } ?: ""

    val flagUrl: String?
        get() = countryFlagImage?.url
}

data class CountryFlagImage(
    @Json(name = "url") val url: String? = null
)

data class LiveTvStream(
    @Json(name = "streamId") val streamId: String? = null,
    @Json(name = "channelId") val channelId: String? = null,
    @Json(name = "name") val name: String? = "",
    @Json(name = "url") val url: String,
    @Json(name = "urlType") val urlType: String? = "hls"
)

data class BffLiveTvChannel(
    @Json(name = "channelId") val channelId: String,
    @Json(name = "title") val title: String,
    @Json(name = "countryCode") val countryCode: String? = null,
    @Json(name = "countryName") val countryName: String? = null,
    @Json(name = "countryFlag") val countryFlag: String? = null,
    @Json(name = "countryFlagImage") val countryFlagImage: CountryFlagImage? = null,
    @Json(name = "languageCodes") val languageCodes: List<String> = emptyList(),
    @Json(name = "streams") val streams: List<LiveTvStream> = emptyList()
) {
    val streamUrl: String?
        get() = streams.firstOrNull()?.url

    val flagUrl: String?
        get() = countryFlagImage?.url
}

data class LiveTvChannelListResponse(
    @Json(name = "code") val code: Int? = 0,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: LiveTvChannelListData? = null
)

data class LiveTvChannelListData(
    @Json(name = "items") val items: List<BffLiveTvChannel> = emptyList()
)

data class LiveTvCountryListResponse(
    @Json(name = "code") val code: Int? = 0,
    @Json(name = "message") val message: String? = null,
    @Json(name = "data") val data: LiveTvCountryListData? = null
)

data class LiveTvCountryListData(
    @Json(name = "items") val items: List<LiveTvCountry> = emptyList()
)
