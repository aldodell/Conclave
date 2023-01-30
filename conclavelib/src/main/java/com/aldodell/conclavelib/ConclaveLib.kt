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
 * Objetivo: Administrar las acciones necesarias para determinar si
 * se cuenta con una aplicacion con licencia del desarrollador.
 *
 * Funciones:
 * 1 - Determinar si es la primera vez que se usa la app
 * 2 - Guardar la fecha del primer uso a efectos de la funcion 1
 * 3 - Estabablecer conexion con servidor para determinar si se ha pagado la app
 * 4 - En caso de estar en periodo de gracia dar acceso al uso
 * 5 - En caso de no estar paga y haberse vencido el periodo de gracia cerrarla.
 */


/**
 * Conclave class. Handle access to an app
 * @param mainActivity  Context object implementation
 * @param AppID  A string wich identify universally the application to be validate
 * @param onFirstTime Method wich will be call at first time use app. And only once.
 */
class Conclave(
    private val mainActivity: AppCompatActivity,
    private val AppID: String,
    val onFirstTime: ((conclave: Conclave) -> (Unit))? = null
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
    var status: AppStatusValidation
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
        mainActivity.getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE)!!

    /**
     *
     * Times wich could be open the app without check licence
     */
    var timesBeforeCheckLicence: Int
        get() = preferences.getInt(TIMES_FOR_CHECK_LICENCES, 0)
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
    private var isActive: Boolean
        get() {
            val s = preferences
                .getString(STATUS, AppStatusValidation.NO_YET.name)
            return (s == AppStatusValidation.ACCEPTED.name) || (s == AppStatusValidation.PENDING.name)
        }
        set(value) {
            var v = AppStatusValidation.ACCEPTED
            if (!value) {
                v = AppStatusValidation.REJECTED
            }
            preferences
                .edit()
                .putString(STATUS, v.name)
                .apply()
        }


    /**
     * Return true if times to free are not over. Substract a time.
     */
    private val getPass: Boolean
        get() {
            if (timesBeforeCheckLicence > 0) {
                timesBeforeCheckLicence -= 1
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

        db.document("apps/$AppID/users/$userEmail")
            .get()
            .addOnCompleteListener {
                if (!it.result.exists()) {
                    //if not exits this app validate then validate
                    val data = mapOf("status" to AppStatusValidation.PENDING)
                    db.document("apps/$AppID/users/$userEmail")
                        .set(data)
                } else {
                    //If existe read status

                    db.document("apps/$AppID/users/$userEmail")
                        .get()
                        .addOnCompleteListener {
                            if (it.isSuccessful) {
                                status =
                                    AppStatusValidation.fromValue((it.result.get("status") as String))
                                isActive = (status == AppStatusValidation.ACCEPTED)
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
    private val signInLauncher: ActivityResultLauncher<Intent> = mainActivity
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
            FirebaseApp.initializeApp(mainActivity, option, CONCLAVE_NAME)
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

/**
 * Se crea la instancia
 * Se invoca onFirstTime
 * Se revisa si est'a registrado el usuario localmente. Si esta activo localmente
 * Si no es as'i se intenta conectar a internet para hacer el proceso de validacion
 * Si es valido en el servidor se guarda localmente
 */
