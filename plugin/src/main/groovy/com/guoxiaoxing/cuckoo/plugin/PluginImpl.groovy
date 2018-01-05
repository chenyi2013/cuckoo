package com.guoxiaoxing.cuckoo.plugin

import com.android.build.gradle.AppExtension
import com.android.build.gradle.AppPlugin
import org.gradle.api.Plugin
import org.gradle.api.Project

class PluginImpl implements Plugin<Project> {

    @Override
    void apply(Project project) {

        def isApp = project.plugins.hasPlugin(AppPlugin)
        if(isApp){
            def android = project.extensions.getByType(AppExtension)
            def costTimeTransform = new CostTimeTransform()
            android.registerTransform(costTimeTransform)
        }

        project.task('testTask') << {
            println("Hello gradle plugin")
        }
    }
}