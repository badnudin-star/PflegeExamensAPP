package de.pflegeexamen.app.data.update

/** Android's installer performs its own cryptographic signature check as well. */
internal object UpdateTrust {
    fun accepts(installedPackage: String, installedCode: Long, installedSigners: Set<String>,
                candidatePackage: String, candidateCode: Long, candidateSigners: Set<String>, expectedCode: Long): Boolean =
        installedPackage.isNotBlank() && candidatePackage == installedPackage && candidateCode == expectedCode &&
            candidateCode > installedCode && installedSigners.isNotEmpty() && candidateSigners == installedSigners
}
