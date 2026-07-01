#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint apivideo_live_stream.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'apivideo_live_stream'
  s.version          = '0.0.1'
  s.summary          = 'A new flutter plugin project.'
  s.description      = <<-DESC
A new flutter plugin project.
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'Classes/**/*'
  s.dependency 'Flutter'
  # SWAN: ApiVideoLiveStream (+ its HaishinKit/Logboard deps) are vendored as
  # prebuilt xcframeworks instead of the CocoaPods dependency. HaishinKit 1.9.3
  # crashes swift-frontend (SIL ownership verifier in MixerNode) under Xcode 26
  # whole-module optimization. The xcframeworks are prebuilt with the crash
  # worked around, so consumers never recompile HaishinKit and no longer need a
  # per-app Podfile post_install hack. Rebuild via ios/Frameworks/BUILD.md.
  s.vendored_frameworks = 'Frameworks/ApiVideoLiveStream.xcframework', 'Frameworks/HaishinKit.xcframework', 'Frameworks/Logboard.xcframework'
  s.platform = :ios, '13.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'
end
