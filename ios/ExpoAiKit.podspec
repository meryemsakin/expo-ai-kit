require 'json'

package = JSON.parse(File.read(File.join(__dir__, '..', 'package.json')))

# Opt-in LLM (Apple Foundation Models + LiteRT-LM). Off by default: the LiteRT-LM
# xcframework (~30 MB of arm64 code) is neither downloaded nor linked, and the
# text-generation functions throw LLM_NOT_ENABLED. The expo-ai-kit config plugin
# writes "expoAiKit.llm": "true" to ios/Podfile.properties.json at prebuild for
# ["expo-ai-kit", { "llm": true }]; a bare React Native app can set
# `$ExpoAiKitLLM = true` in its Podfile instead. Speech, vision, and embeddings
# use OS frameworks on iOS and need no option here.
# (A bare `rescue` is deliberate: inside a podspec, `StandardError` resolves
# to Pod::StandardError and would not catch a missing or malformed file.)
podfile_properties = begin
  path = File.join(Pod::Config.instance.installation_root, 'Podfile.properties.json')
  File.exist?(path) ? JSON.parse(File.read(path)) : {}
rescue
  {}
end
llm_enabled = podfile_properties['expoAiKit.llm'] == 'true' ||
  (defined?($ExpoAiKitLLM) && $ExpoAiKitLLM == true)
Pod::UI.puts "[ExpoAiKit] LLM (Foundation Models + LiteRT-LM): #{llm_enabled ? 'enabled' : 'disabled, set expoAiKit.llm to enable'}"

Pod::Spec.new do |s|
  s.name           = 'ExpoAiKit'
  s.version        = package['version']
  s.summary        = package['description']
  s.description    = package['description']
  s.license        = package['license']
  s.author         = package['author']
  s.homepage       = package['homepage']
  s.platforms      = {
    :ios => '15.1',
    :tvos => '15.1'
  }
  s.swift_version  = '5.9'
  s.source         = { git: 'https://github.com/saidkaban/expo-ai-kit' }

  s.dependency 'ExpoModulesCore'

  if llm_enabled
    # LiteRT-LM C xcframework is downloaded into ios/Vendor/ on pod install.
    # Swift wrapper sources (Apache 2.0) live alongside in ios/Vendor/LiteRTLM/.
    s.prepare_command = 'bash ../scripts/install-litertlm.sh'
    s.vendored_frameworks = 'Vendor/CLiteRTLM.xcframework'
    s.source_files = [
      '*.swift',
      'Vendor/LiteRTLM/*.swift',
    ]
  else
    s.source_files = ['*.swift']
    s.exclude_files = ['GemmaInferenceClient.swift']
  end

  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    # Gates the Foundation Models and LiteRT-LM code in ExpoAiKitModule.swift.
    'SWIFT_ACTIVE_COMPILATION_CONDITIONS' => llm_enabled ? '$(inherited) EXPO_AI_KIT_LLM' : '$(inherited)',
  }
end
