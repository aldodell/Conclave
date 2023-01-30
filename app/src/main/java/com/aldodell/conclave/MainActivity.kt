package com.aldodell.conclave

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import com.aldodell.conclavelib.Conclave


class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val lib = Conclave(this, "ConclaveTest") {
            it.timesBeforeCheckLicence = 0
        }

        val isValid = lib.isValid
val z=0


    }
}