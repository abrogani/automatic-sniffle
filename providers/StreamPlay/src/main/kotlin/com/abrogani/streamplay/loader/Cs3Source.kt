package com.abrogani.streamplay.loader

/**
 * Where the upstream StreamPlay plugin (phisher98/cloudstream-extensions-phisher) is loaded from.
 *
 * It is pinned to a commit and a checksum on purpose: the `builds` branch is a moving target and
 * whatever is downloaded here runs inside the host app. To follow upstream, bump [COMMIT] and
 * [SHA256] together after checking the new build.
 */
internal object Cs3Source {
    const val COMMIT = "3b67c552158a9dc0f898069974a3a9838743bd64"
    const val SHA256 = "fc779f9c8e07afba3cca8a9c63c58cd3c40b514cbb4369b6115ad466bf3eca00"
    const val URL = "https://github.com/phisher98/cloudstream-extensions-phisher/raw/$COMMIT/StreamPlay.cs3"
}
