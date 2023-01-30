package com.aldodell.conclavelib

import android.app.Activity.MODE_PRIVATE
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import com.firebase.ui.auth.AuthUI
import com.firebase.ui.auth.AuthUI.IdpConfig
import com.firebase.ui.auth.FirebaseAuthUIActivityResultContract
import com.firebase.ui.auth.data.model.FirebaseAuthUIAuthenticationResult
import com.google.firebase.FirebaseApp

import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.time.Instant
import java.util.Date


/**
 * Conclave class. Handle access to an app
 * @param appCompatActivity  Activity instance call validation app.
 * @param AppID  A string which identify universally the application to be validate
 * @param onFirstTime Method which will be call at first time use app. And only once.
 *
 * Example:
 * val lib = Conclave(this, "ConclaveTest") {
 *      it.timesBeforeCheckLicence = 0
 *  }
 *
 *  lib.checkValidity {
 *      //Do some stuff
 *   }
 *
 *
 *
 */
class Conclave(
    private val appCompatActivity: AppCompatActivity,
    private val AppID: String,
    private val onFirstTime: ((conclave: Conclave) -> (Unit))? = null
) {

    //Late objects
    private lateinit var firebaseApp: FirebaseApp

    // Keys
    private val PREFERENCES_NAME = "conclave"
    private val FIRST_DATE = "first_date"
    private val TIMES_FOR_CHECK_LICENCES = "times_for_free"
    private val STATUS = "STATUS"
    private val CONCLAVE_ID = "1:877171259644:android:9503714834ab45415ebf79"
    private val API_KEY = "AIzaSyCo7oGxz2R7LO84qUzKjRjj19y3yTwN8iw"
    private val CONCLAVE_NAME = "Conclave"
    private val PROJECT_ID = "controlpagoaplicativo"


    /**
     * Status enumeration
     */
    enum class AppStatusValidation(val value: String) {
        NO_YET("NO_YET"),
        PENDING("PENDING"),
        ACCEPTED("ACCEPTED"),
        REJECTED("REJECTED");

        companion object {
            fun fromValue(s: String) = AppStatusValidation.values().first { it.value == s }
        }
    }

    /**
     * Validation status
     */
    private var status: AppStatusValidation
        get() {
            return AppStatusValidation.valueOf(
                preferences.getString(
                    STATUS,
                    AppStatusValidation.NO_YET.name
                )!!
            )
        }
        set(value) {
            preferences
                .edit()
                .putString(STATUS, value.name)
                .apply()
        }


    /**
     * Invalid user mail exception
     */
    class InvalidUserEmail : Exception("Invalid user email")

    /**
     * Invalid licence Exception
     */
    class InvalidLicenceApplication : java.lang.Exception("Invalid licence")


    /**
     *  Common  preferences
     */
    private val preferences: SharedPreferences =
        appCompatActivity.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)!!

    /**
     *
     * Times which could be open the app without check licence.
     * Works like a trial mode. Once "times" are gone
     * status will change to REJECTED.
     */
    var timesBeforeCheckLicence: Int
        get() = preferences.getInt(TIMES_FOR_CHECK_LICENCES, 1)
        set(value) {
            preferences
                .edit()
                .putInt(TIMES_FOR_CHECK_LICENCES, value)
                .apply()
        }


    /**
     * Return true if is first time use
     */
    private val isFirstTime: Boolean
        get() {
            return if (preferences.getString(FIRST_DATE, "")!!.isEmpty()) {
                val date = Date.from(Instant.now())
                    .toString() //FIXME Because Instant.now it is necessary level 26
                preferences
                    .edit()
                    .putString(FIRST_DATE, date)
                    .apply()
                true
            } else {
                false
            }
        }

    /**
     * Is active, return true if de app was activate from server
     */
    private val isActive: Boolean
        get() {
            return status == AppStatusValidation.PENDING || status == AppStatusValidation.ACCEPTED
        }


    /**
     * Return true if times to free are not over. Substract a time.
     */
    private val getPass: Boolean
        get() {
            if (timesBeforeCheckLicence > 0) {
                timesBeforeCheckLicence -= 1
            }
            if ((timesBeforeCheckLicence < 1) && (status == AppStatusValidation.PENDING)) {
                status = AppStatusValidation.REJECTED
            }
            return timesBeforeCheckLicence > 0
        }


    /**
     * Callback call to detect where validation process are done.
     */
    private var isValidCallback: ((isValid: Boolean) -> (Unit))? = null

    /**
     * Return true if the app could be used. Regardless if for trial time or because is valid.
     * If return value are true but the app aren't until valid, will invoke getPass and will substract
     * internal value of timesBeforeCheckLicence.
     */
    fun checkValidity(callback: (isValid: Boolean) -> (Unit)): Unit {

        isValidCallback = callback
        signIn()

        //If active
        if (isActive) {
            callback(true)
            return
        }

        //If doesnt active but it on trial
        if (getPass) {
            callback(true)
            return
        }

    }


    private fun checkIfAppIsAlreadyValidate(userEmail: String) {

        //Get  database
        val db = FirebaseFirestore.getInstance(firebaseApp)

        //Check if existe user finding its email
        db.document("apps/$AppID/users/$userEmail")
            .get()
            .addOnCompleteListener {
                if (!it.result.exists()) {
                    //if not exits this app validate then validate
                    val data = mapOf("status" to AppStatusValidation.PENDING)
                    db.document("apps/$AppID/users/$userEmail")
                        .set(data)
                } else {
                    //If exist read status
                    db.document("apps/$AppID/users/$userEmail")
                        .get()
                        .addOnCompleteListener {
                            if (it.isSuccessful) {
                                status =
                                    AppStatusValidation.fromValue((it.result.get("status") as String))
                                isValidCallback?.invoke(isActive)
                            }
                        }
                }
            }
    }


    private fun onSignInResult(result: FirebaseAuthUIAuthenticationResult) {
        // val response = result.idpResponse
        if (result.resultCode == RESULT_OK) {
            try {
                //Get email form current user
                val userEmail = FirebaseAuth.getInstance(firebaseApp).currentUser!!.email!!
                //Check y this app are authorized
                checkIfAppIsAlreadyValidate(userEmail)
            } catch (e: Exception) {
                Log.e("onSignInResult", e.message!!)
                throw InvalidUserEmail()
            }
        }
    }


    /**
     * Begins signin process
     */
    private val signInLauncher: ActivityResultLauncher<Intent> = appCompatActivity
        .registerForActivityResult(FirebaseAuthUIActivityResultContract()) { result ->
            onSignInResult(result)
        }


    /**
     * Try to syncronize with server
     */
    fun signIn() {
        val option = FirebaseOptions.Builder()
            .setApplicationId(CONCLAVE_ID)
            .setApiKey(API_KEY)
            .setProjectId(PROJECT_ID)
            .build()

        firebaseApp = try {
            FirebaseApp.getInstance(CONCLAVE_NAME)
        } catch (ex: Exception) {
            FirebaseApp.initializeApp(appCompatActivity, option, CONCLAVE_NAME)
            // FirebaseApp.getInstance(CONCLAVE_NAME)
        }

        //Get AuthUI
        val authUI = AuthUI.getInstance(firebaseApp)

        //Configure intent
        val intento = authUI.createSignInIntentBuilder()
            .setAvailableProviders(listOf(IdpConfig.GoogleBuilder().build()))
            .build()

        //call launcher
        signInLauncher.launch(intento)
    }


    init {
        if (isFirstTime) {

            //Save NOT YET as first status
            status = AppStatusValidation.NO_YET

            //Invoking user custom onFirstTime method
            onFirstTime?.invoke(this)

        }

    }
}

