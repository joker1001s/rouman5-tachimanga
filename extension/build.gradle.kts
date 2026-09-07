import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Rouman5"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        name = "肉漫屋"
        lang = "zh"
        baseUrl = "https://rouman5.com"
        id = 6000000000000099L
    }

    deeplink {
        path("/books/..*")
    }
}
