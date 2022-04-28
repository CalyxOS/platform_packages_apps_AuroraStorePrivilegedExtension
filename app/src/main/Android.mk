LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_PACKAGE_NAME := AuroraStorePrivilegedExtension
LOCAL_MODULE_TAGS := optional
LOCAL_SDK_VERSION := current
# Keep IPackageInstallObserver and IPackageDeleteObserver
LOCAL_PROGUARD_ENABLED := disabled
LOCAL_SRC_FILES := $(call all-java-files-under, java) \
                   $(call all-Iaidl-files-under, aidl)
LOCAL_AIDL_INCLUDES := $(LOCAL_PATH)/aidl
LOCAL_PRODUCT_MODULE := true
include $(BUILD_PACKAGE)
