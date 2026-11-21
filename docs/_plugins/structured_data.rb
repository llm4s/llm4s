# frozen_string_literal: true

# Keeps one JSON-LD description per page (#2316). jekyll-seo-tag always writes its own block (a WebSite or WebPage)
# and has no switch to leave it out. On a page whose _includes/structured-data.html writes a block marked
# `data-replaces-seo-tag` (the home page's SoftwareSourceCode, /foundation/'s Organization), the generic block would
# describe the same page as something else, so it is removed here, after rendering.
#
# On every other page jekyll-seo-tag's block stays, with its image fixed: jekyll-seo-tag types it `imageObject` and
# copies the front matter's `alt` into it, neither of which Schema.org has (it is an `ImageObject` with a `caption`),
# so validators report both.
require "json"

module Llm4sStructuredData
  MARKER = "data-replaces-seo-tag"
  SEO_BLOCK = %r{(<!-- Begin Jekyll SEO tag.*?)<script type="application/ld\+json">.*?</script>\s*(<!-- End Jekyll SEO tag -->)}m.freeze
  SEO_JSON_LD = %r{(<!-- Begin Jekyll SEO tag.*?<script type="application/ld\+json">)(.*?)(</script>)}m.freeze

  def self.rewrite(html)
    head_end = html.index("</head>")
    return html unless head_end

    head = html[0...head_end]
    head = if head.include?(MARKER)
             head.sub(SEO_BLOCK) { "#{Regexp.last_match(1)}#{Regexp.last_match(2)}" }
           else
             head.sub(SEO_JSON_LD) do
               m = Regexp.last_match
               "#{m[1]}#{fix_image(m[2])}#{m[3]}"
             end
           end
    head + html[head_end..]
  end

  def self.fix_image(json)
    data = JSON.parse(json)
    image = data["image"]
    return json unless image.is_a?(Hash)

    image["@type"] = "ImageObject"
    caption = image.delete("alt")
    image["caption"] = caption if caption
    JSON.generate(data)
  rescue JSON::ParserError
    json
  end
end

Jekyll::Hooks.register [:pages, :documents], :post_render do |doc|
  doc.output = Llm4sStructuredData.rewrite(doc.output) if doc.output_ext == ".html" && doc.output
end
