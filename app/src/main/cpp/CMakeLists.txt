cmake_minimum_required(VERSION 3.22.1)
project(emptyset_rx)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 11)

get_filename_component(PROJECT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)
set(DEVOURER_DIR "${PROJECT_ROOT}/third_party/devourer")
set(LIBUSB_DIR "${PROJECT_ROOT}/third_party/libusb")

# libusb (LGPL-2.1, shared)
add_library(usb-1.0 SHARED
    ${LIBUSB_DIR}/libusb/core.c
    ${LIBUSB_DIR}/libusb/descriptor.c
    ${LIBUSB_DIR}/libusb/hotplug.c
    ${LIBUSB_DIR}/libusb/io.c
    ${LIBUSB_DIR}/libusb/sync.c
    ${LIBUSB_DIR}/libusb/strerror.c
    ${LIBUSB_DIR}/libusb/os/linux_usbfs.c
    ${LIBUSB_DIR}/libusb/os/events_posix.c
    ${LIBUSB_DIR}/libusb/os/threads_posix.c
    ${LIBUSB_DIR}/libusb/os/linux_netlink.c
)
target_include_directories(usb-1.0
    PUBLIC
        ${LIBUSB_DIR}/libusb
    PRIVATE
        ${LIBUSB_DIR}/android
        ${LIBUSB_DIR}/libusb/os
)
target_compile_definitions(usb-1.0 PRIVATE HAVE_CONFIG_H _GNU_SOURCE)
target_compile_options(usb-1.0 PRIVATE -fvisibility=hidden -pthread)
target_link_libraries(usb-1.0 PRIVATE log)

# devourer Jaguar1 RX core (GPL-2.0). TX demos and other chip HALs stay out.
add_library(devourer STATIC
    ${DEVOURER_DIR}/src/HalmacCfgParam.cpp
    ${DEVOURER_DIR}/src/ParsedRadioPacket.cpp
    ${DEVOURER_DIR}/src/Radiotap.c
    ${DEVOURER_DIR}/src/RadiotapBuilder.cpp
    ${DEVOURER_DIR}/src/RtlAdapter.cpp
    ${DEVOURER_DIR}/src/UsbTransport.cpp
    ${DEVOURER_DIR}/src/UsbDeviceLock.cpp
    ${DEVOURER_DIR}/src/UsbOpen.cpp
    ${DEVOURER_DIR}/src/TxMode.cpp
    ${DEVOURER_DIR}/src/TxPower.cpp
    ${DEVOURER_DIR}/src/LinkHealth.cpp
    ${DEVOURER_DIR}/src/chanmig/ChannelScore.cpp
    ${DEVOURER_DIR}/src/chanmig/MigGate.cpp
    ${DEVOURER_DIR}/src/sensing/SenseWindow.cpp
    ${DEVOURER_DIR}/src/SignalStop.cpp
    ${DEVOURER_DIR}/src/WiFiDriver.cpp
    ${DEVOURER_DIR}/src/LaCapture.cpp
    ${DEVOURER_DIR}/src/PhyTableLoader.cpp
    ${DEVOURER_DIR}/hal/hal8812a_fw.c
    ${DEVOURER_DIR}/hal/hal8821a_fw.c
    ${DEVOURER_DIR}/hal/Hal8812PwrSeq.c
    ${DEVOURER_DIR}/hal/Hal8821APwrSeq.c
    ${DEVOURER_DIR}/hal/Hal8812a_TxPwrTrack.cpp
    ${DEVOURER_DIR}/src/jaguar1/RtlJaguarDevice.cpp
    ${DEVOURER_DIR}/src/jaguar1/HalModule.cpp
    ${DEVOURER_DIR}/src/jaguar1/RadioManagementModule.cpp
    ${DEVOURER_DIR}/src/jaguar1/EepromManager.cpp
    ${DEVOURER_DIR}/src/jaguar1/FirmwareManager.cpp
    ${DEVOURER_DIR}/src/jaguar1/Iqk8812a.cpp
    ${DEVOURER_DIR}/src/jaguar1/PhydmWatchdog.cpp
    ${DEVOURER_DIR}/src/jaguar1/PowerTracking8812a.cpp
    ${DEVOURER_DIR}/src/jaguar1/FrameParser.cpp
    ${DEVOURER_DIR}/src/jaguar1/BbDbgportReader.cpp
    ${DEVOURER_DIR}/hal/hal8814a_fw.c
    ${DEVOURER_DIR}/hal/Hal8814PwrSeq.c
    ${DEVOURER_DIR}/hal/phydm/rtl8814a/Hal8814_PhyTables.c
    ${DEVOURER_DIR}/src/jaguar1/Iqk8814a.cpp
    ${DEVOURER_DIR}/src/mt7612u/async.cpp
    ${DEVOURER_DIR}/src/mt7612u/beacon.cpp
    ${DEVOURER_DIR}/src/mt7612u/caps.cpp
    ${DEVOURER_DIR}/src/mt7612u/eeprom.cpp
    ${DEVOURER_DIR}/src/mt7612u/fw.cpp
    ${DEVOURER_DIR}/src/mt7612u/init.cpp
    ${DEVOURER_DIR}/src/mt7612u/mcu.cpp
    ${DEVOURER_DIR}/src/mt7612u/phy.cpp
    ${DEVOURER_DIR}/src/mt7612u/radiotap.cpp
    ${DEVOURER_DIR}/src/mt7612u/rx.cpp
    ${DEVOURER_DIR}/src/mt7612u/tx.cpp
    ${DEVOURER_DIR}/src/mt7612u/usb.cpp
    ${DEVOURER_DIR}/src/mt7612u/Mt7612uRadio.cpp
)
target_include_directories(devourer PUBLIC
    ${DEVOURER_DIR}/src
    ${DEVOURER_DIR}/src/jaguar1
    ${DEVOURER_DIR}/hal
    ${DEVOURER_DIR}/hal/phydm/rtl8814a
    ${DEVOURER_DIR}/src/mt7612u/include
)
target_include_directories(devourer PRIVATE
    ${DEVOURER_DIR}/src/mt7612u
)
target_compile_definitions(devourer PUBLIC DEVOURER_HAVE_JAGUAR1=1 DEVOURER_HAVE_8814=1 DEVOURER_HAVE_MT7612U=1)
target_compile_options(devourer PRIVATE
    -Wno-unused-parameter
    -Wno-unused-variable
    -Wno-unused-function
    -Wno-unused-value
    -Wno-missing-field-initializers
)
target_link_libraries(devourer PUBLIC usb-1.0 log)

set(RTLSDR_DIR "${PROJECT_ROOT}/third_party/rtl-sdr")
add_library(rtlsdr STATIC
    ${RTLSDR_DIR}/src/librtlsdr.c
    ${RTLSDR_DIR}/src/tuner_e4k.c
    ${RTLSDR_DIR}/src/tuner_fc0012.c
    ${RTLSDR_DIR}/src/tuner_fc0013.c
    ${RTLSDR_DIR}/src/tuner_fc2580.c
    ${RTLSDR_DIR}/src/tuner_r82xx.c
)
target_include_directories(rtlsdr PUBLIC ${RTLSDR_DIR}/include)
target_compile_definitions(rtlsdr PRIVATE rtlsdr_STATIC DETACH_KERNEL_DRIVER)
target_compile_options(rtlsdr PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -fvisibility=hidden
)
target_link_libraries(rtlsdr PUBLIC usb-1.0 log)

set(HACKRF_DIR "${PROJECT_ROOT}/third_party/hackrf")
add_library(hackrf STATIC ${HACKRF_DIR}/src/hackrf.c)
target_include_directories(hackrf PUBLIC ${HACKRF_DIR}/include)
target_compile_definitions(hackrf PRIVATE LIBRARY_VERSION="android" LIBRARY_RELEASE="android")
target_compile_options(hackrf PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -fvisibility=hidden
)
target_link_libraries(hackrf PUBLIC usb-1.0 log)

add_library(emptyset_rx SHARED
    emptyset_rx.cpp
    emptyset_sdr.cpp
    emptyset_hackrf.cpp
    emptyset_mt76.cpp
    mt7610u/usb.cpp
    mt7610u/mcu.cpp
    mt7610u/fw.cpp
    mt7610u/radio.cpp
)
target_include_directories(emptyset_rx PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(emptyset_rx PRIVATE devourer rtlsdr hackrf usb-1.0 log android)
target_compile_options(emptyset_rx PRIVATE -fexceptions -frtti)

set(AIRSPY_DIR "${PROJECT_ROOT}/third_party/airspy")
add_library(airspy STATIC
    ${AIRSPY_DIR}/airspy.c
    ${AIRSPY_DIR}/iqconverter_float.c
    ${AIRSPY_DIR}/iqconverter_int16.c
)
target_include_directories(airspy PUBLIC ${AIRSPY_DIR} ${LIBUSB_DIR}/libusb)
target_compile_definitions(airspy PRIVATE _GNU_SOURCE)
target_compile_options(airspy PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -fvisibility=hidden
    -pthread
)
target_link_libraries(airspy PUBLIC usb-1.0 log)

add_library(emptyset_airspy SHARED emptyset_airspy.cpp)
target_include_directories(emptyset_airspy PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(emptyset_airspy PRIVATE airspy usb-1.0 log)
target_compile_options(emptyset_airspy PRIVATE -fexceptions -frtti)

set(AIRSPYHF_DIR "${PROJECT_ROOT}/third_party/airspyhf")
add_library(airspyhf STATIC
    ${AIRSPYHF_DIR}/airspyhf.c
    ${AIRSPYHF_DIR}/iqbalancer.c
)
target_include_directories(airspyhf PUBLIC ${AIRSPYHF_DIR} ${LIBUSB_DIR}/libusb)
target_compile_definitions(airspyhf PRIVATE _GNU_SOURCE)
target_compile_options(airspyhf PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -fvisibility=hidden
    -pthread
)
target_link_libraries(airspyhf PUBLIC usb-1.0 log)

add_library(emptyset_airspyhf SHARED emptyset_airspyhf.cpp)
target_include_directories(emptyset_airspyhf PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(emptyset_airspyhf PRIVATE airspyhf usb-1.0 log)
target_compile_options(emptyset_airspyhf PRIVATE -fexceptions -frtti)

set(HYDRASDR_DIR "${PROJECT_ROOT}/third_party/hydrasdr")
add_library(hydrasdr STATIC
    ${HYDRASDR_DIR}/hydrasdr.c
    ${HYDRASDR_DIR}/hydrasdr_rfone.c
    ${HYDRASDR_DIR}/hydrasdr_shared.c
    ${HYDRASDR_DIR}/iqconverter.c
    ${HYDRASDR_DIR}/iqconverter_decimator.c
    ${HYDRASDR_DIR}/iqconverter_lut.c
    ${HYDRASDR_DIR}/iqconverter_float.c
    ${HYDRASDR_DIR}/iqconverter_float_opt.c
    ${HYDRASDR_DIR}/iqconverter_float_opt33.c
    ${HYDRASDR_DIR}/iqconverter_float_opt65.c
    ${HYDRASDR_DIR}/iqconverter_float_opt83.c
    ${HYDRASDR_DIR}/iqconverter_float_opt_dec.c
    ${HYDRASDR_DIR}/iqconverter_int16.c
    ${HYDRASDR_DIR}/iqconverter_int16_opt.c
    ${HYDRASDR_DIR}/iqconverter_int16_opt33.c
    ${HYDRASDR_DIR}/iqconverter_int16_opt65.c
    ${HYDRASDR_DIR}/iqconverter_int16_opt83.c
    ${HYDRASDR_DIR}/iqconverter_int16_opt_dec.c
)
target_include_directories(hydrasdr PUBLIC ${HYDRASDR_DIR} ${LIBUSB_DIR}/libusb)
target_compile_definitions(hydrasdr PRIVATE _GNU_SOURCE)
target_compile_options(hydrasdr PRIVATE
    -Wno-unused-parameter
    -Wno-unused-function
    -Wno-unused-variable
    -Wno-unused-but-set-variable
    -fvisibility=hidden
    -pthread
)
target_link_libraries(hydrasdr PUBLIC usb-1.0 log)

add_library(emptyset_hydrasdr SHARED emptyset_hydrasdr.cpp)
target_include_directories(emptyset_hydrasdr PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(emptyset_hydrasdr PRIVATE hydrasdr usb-1.0 log)
target_compile_options(emptyset_hydrasdr PRIVATE -fexceptions -frtti)
