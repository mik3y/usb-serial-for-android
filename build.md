# Build Variants

## jitpack repository (default)

The library is typically consumed from jitpack.io with:

    dependencies {
        implementation 'com.github.kai-morich:usb-serial-for-android:1.x.0
    }

When pushing to github with jitpack enabled, 
then jitpack automatically publishes __*.aar__ and __*-sources.jar__ files, 
even if no `maven` or `maven-publish` gradle plugin is used. 

## jar

If you need a jar file (e.g. for cordova) run gradle task `createFullJarRelease` and use __full.jar__
 
## local maven repository

To use a local maven repository (e.g. for testing new library version) comment in

    //apply from: 'publishToMavenLocal.gradle'

set correct version and run gradle task `publishToMavenLocal`
