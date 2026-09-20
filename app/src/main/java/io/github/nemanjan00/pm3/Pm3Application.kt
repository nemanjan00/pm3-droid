package io.github.nemanjan00.pm3

import android.app.Application
import io.github.nemanjan00.pm3.client.Pm3Runtime

class Pm3Application : Application() {
    val runtime: Pm3Runtime by lazy { Pm3Runtime(this) }
}
