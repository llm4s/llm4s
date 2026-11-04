# frozen_string_literal: true

# A page's `search_title` front-matter key replaces the <title>, og:title and twitter:title that jekyll-seo-tag
# renders, while `title` stays the navigation label (just-the-docs reads `title` for the sidebar, breadcrumbs and
# ordering, so changing it would move the page in the navigation) (#2314).
#
# It rewrites the rendered page, not the page's data: the navigation of every other page is built from `title`, and
# changing it before render would make that depend on the order pages are rendered in.
require "cgi"

module Llm4sSearchTitle
  SITE_SUFFIX = " | "

  def self.rewrite(doc, html)
    search_title = doc.data["search_title"].to_s.strip
    return html if search_title.empty?

    site_title = doc.site.config["title"].to_s
    full = site_title.empty? || search_title.include?(site_title) ? search_title : "#{search_title}#{SITE_SUFFIX}#{site_title}"
    head_end = html.index("</head>") || html.length
    head = html[0...head_end]
    head = head.sub(%r{<title>[^<]*</title>}) { "<title>#{CGI.escapeHTML(full)}</title>" }
    head = head.sub(%r{(<meta property="og:title" content=")[^"]*(")}) { "#{$1}#{CGI.escapeHTML(search_title)}#{$2}" }
    head = head.sub(%r{(<meta property="twitter:title" content=")[^"]*(")}) { "#{$1}#{CGI.escapeHTML(search_title)}#{$2}" }
    head + html[head_end..]
  end
end

Jekyll::Hooks.register [:pages, :documents], :post_render do |doc|
  doc.output = Llm4sSearchTitle.rewrite(doc, doc.output) if doc.data.key?("search_title")
end
