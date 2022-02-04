package com.virginiaprivacy.drivers.desktopusb

import io.kotest.core.spec.style.FunSpec
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldNotBe

class TestClass : StringSpec( {

    "findAnyAvailableDevices function shouldn't return null" {
        DesktopUsbInterface.findAnyAvailableDevice() shouldNotBe null
    }

    "findAnyAvailableDevices returns valid device" {
        DesktopUsbInterface.findAnyAvailableDevice()?.let {
            println(it.manufacturerName)
            it.manufacturerName.length shouldNotBe 0
        }
    }
})