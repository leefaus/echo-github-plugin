package io.armory.plugin.events.listener.github

import com.netflix.spinnaker.kork.plugins.api.ExtensionConfiguration

@ExtensionConfiguration("armory.githubEventListener")
data class GitHubEventListenerConfig(var apiKey: String) {
}