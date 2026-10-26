package com.vadimtoptunov.chaosbank.server

import com.vadimtoptunov.chaosbank.chaos.ActiveDefects
import java.time.LocalDate

/*
 * Identity verification on the server: the apps' `KycReview`, rule for rule. The client
 * sends what its document scanner and face check read; the server decides. Four defects
 * override the rules at single points and share the apps' ids, so an app running against
 * the server forwards them.
 */

enum class KycStatus { unverified, pending, verified, rejected }

data class KycDocument(val kind: String, val name: String?, val number: String?, val expiry: LocalDate?)

enum class KycSelfie { match, mismatch, noFace }

data class KycDecision(val status: KycStatus, val reason: String?)

object KycReview {
    /** The account holder's name as it appears on their documents (the apps' `accountHolder`). */
    const val ACCOUNT_HOLDER = "VADIM TOPTUNOV"

    /** Same order as the apps: expiry, then name, then face. [today] is the session's date. */
    fun decide(document: KycDocument, selfie: KycSelfie, today: LocalDate, chaos: ActiveDefects): KycDecision {
        // `kycAcceptsExpiredDocument`: the expiry date isn't checked.
        if (document.expiry!!.isBefore(today) && !chaos.isActive(ServerDefects.kycAcceptsExpiredDocument)) {
            return KycDecision(KycStatus.rejected, "Document expired")
        }
        // `kycNameMismatchAccepted`: the document's name isn't compared with the account holder.
        if (document.name != ACCOUNT_HOLDER && !chaos.isActive(ServerDefects.kycNameMismatchAccepted)) {
            return KycDecision(KycStatus.rejected, "Name doesn't match the account holder")
        }
        // `kycSelfieMismatchAccepted`: a face that doesn't match still passes.
        if (selfie == KycSelfie.mismatch && !chaos.isActive(ServerDefects.kycSelfieMismatchAccepted)) {
            return KycDecision(KycStatus.rejected, "Face doesn't match the document")
        }
        // `kycStuckPending`: an approved check is reported as still under review.
        return if (chaos.isActive(ServerDefects.kycStuckPending)) KycDecision(KycStatus.pending, null)
        else KycDecision(KycStatus.verified, null)
    }
}

/** One session's verification state. Starts verified, as the apps do unless launched unverified. */
class KycState {
    @Volatile var decision = KycDecision(KycStatus.verified, null); private set

    @Synchronized
    fun submit(document: KycDocument, selfie: KycSelfie, today: LocalDate, chaos: ActiveDefects): KycDecision {
        if (document.name == null || document.number == null || document.expiry == null)
            throw BankException(BankError.invalidDocument, "the document scan is unreadable")
        if (selfie == KycSelfie.noFace) throw BankException(BankError.noFace, "no face detected")
        return KycReview.decide(document, selfie, today, chaos).also { decision = it }
    }
}
