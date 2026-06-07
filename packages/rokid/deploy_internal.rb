#!/usr/bin/env ruby
require 'bundler/setup'
require 'supply'
require 'fastlane_core'

aab = File.join(__dir__, "app/build/outputs/bundle/release/app-release.aab")
unless File.exist?(aab)
  puts "ERROR: AAB not found at #{aab}"
  exit 1
end

puts "Uploading #{aab} to internal track..."

Supply.config = FastlaneCore::Configuration.create(
  Supply::Options.available_options,
  {
    track: "internal",
    aab: aab,
    skip_upload_apk: true,
    skip_upload_metadata: true,
    skip_upload_images: true,
    skip_upload_screenshots: true,
    json_key: File.join(__dir__, "fastlane", "play-store-credentials.json"),
    package_name: "id.infinia.porta"
  }
)
Supply::Uploader.new.perform_upload
puts "✅ Uploaded to internal track!"
