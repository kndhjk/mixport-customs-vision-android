# Preserve updater JSON fields read by Gson in the minified release build.
-keep class nz.co.mixport.customsvision.update.AppUpdateManager$GithubRelease { *; }
-keep class nz.co.mixport.customsvision.update.AppUpdateManager$GithubReleaseAsset { *; }
-keep class nz.co.mixport.customsvision.update.UpdateManifest { *; }
-keep class nz.co.mixport.customsvision.update.AppUpdateRelease { *; }
