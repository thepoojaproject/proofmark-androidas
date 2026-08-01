package com.proofmark.qrstudio

import android.app.Application

/**
 * Application entry point. Kept intentionally minimal — all of the app's
 * real behavior lives in the embedded web bundle under `assets/`, so
 * there's no app-wide state to initialize beyond the default Android
 * component setup.
 */
class ProofmarkApplication : Application()
