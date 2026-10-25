// use an integer for version numbers
version = 9


cloudstream {
    // All of these properties are optional, you can safely remove them

    description = "Anime vietsub - lồng tiếng từ animevietsub.sale"
    language    = "vi"
    authors = listOf("Aho-Repo")

    /**
    * Status int as the following:
    * 0: Down
    * 1: Ok
    * 2: Slow
    * 3: Beta only
    * */
    status = 3 // Beta — chưa verify runtime player sau gate Turnstile; đổi thành 1 khi đã test ổn

    // List of video source types. Users are able to filter for extensions in a given category.
    // You can find a list of available types here:
    // https://recloudstream.github.io/cloudstream/html/app/com.lagradost.cloudstream3/-tv-type/index.html
    tvTypes = listOf("Anime", "AnimeMovie")
    iconUrl = "https://animevietsub.sale/favicon.ico"

    isCrossPlatform = false
}
