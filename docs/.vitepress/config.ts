import { defineConfig, type HeadConfig } from 'vitepress'

// jp4 documentation site configuration.
//
// Site identity:
//   - hostname: https://zhh2001.github.io
//   - base path: /jp4/
//   - root locale: English (en-US); secondary locale: Simplified Chinese (zh-CN) at /zh/
//
// SEO infrastructure:
//   - per-page title / description / keywords meta (via VitePress frontmatter + global head)
//   - OpenGraph tags (global + per-page)
//   - canonical URLs (absolute, computed per page)
//   - hreflang link tags (en + zh, absolute URLs, computed per page)
//   - sitemap.xml (VitePress built-in, hostname-absolute)
//   - robots.txt (docs/public/robots.txt, allow all + sitemap pointer)
//
// Forward-looking content: none. All copy describes
// present state; roadmap and "coming soon" content live in GitHub
// Issues / Discussions, not on this site.

const SITE_HOSTNAME = 'https://zhh2001.github.io'
const SITE_BASE     = '/jp4/'
const SITE_URL      = SITE_HOSTNAME + SITE_BASE   // 'https://zhh2001.github.io/jp4/'

const TITLE_EN       = 'jp4'
const TITLE_ZH       = 'jp4'
const DESCRIPTION_EN = 'A native Java client library for P4Runtime — connect to a P4Runtime-enabled device, push pipelines, read and write table and PRE entries, and send and receive packets through the StreamChannel.'
const DESCRIPTION_ZH = '面向 P4Runtime 的原生 Java 客户端库 —— 连接 P4Runtime 设备、下发 pipeline、读写 table 和 PRE 条目,并通过 StreamChannel 收发数据包。'

/**
 * Per-page head tags that depend on the page URL: canonical link,
 * hreflang alternates, OpenGraph URL. VitePress invokes `transformHead`
 * for every page at build time with the page context (`pageData.relativePath`,
 * `pageData.lang`, …); we compute absolute URLs from `SITE_URL` and the page's
 * own routed path.
 */
function pageHead(pageData: any): HeadConfig[] {
  const relPath: string = pageData.relativePath || ''
  // VitePress routes 'foo/index.md' -> 'foo/', 'foo.md' -> 'foo.html'.
  // For canonical/hreflang we want clean URLs with trailing slash for
  // index pages and `.html` for leaf pages, mirroring VitePress's own
  // generated link structure.
  const routed = relPath
    .replace(/index\.md$/, '')
    .replace(/\.md$/, '.html')
  const pageAbsoluteUrl = SITE_URL + routed

  // hreflang alternates: compute the en and zh variants of the current page.
  // Pages under /zh/ swap to / for the en variant; pages at root swap
  // their leading segment to /zh/.
  let enPath: string
  let zhPath: string
  if (routed.startsWith('zh/')) {
    zhPath = routed
    enPath = routed.slice('zh/'.length)
  } else {
    enPath = routed
    zhPath = 'zh/' + routed
  }
  const enUrl = SITE_URL + enPath
  const zhUrl = SITE_URL + zhPath

  return [
    ['link', { rel: 'canonical', href: pageAbsoluteUrl }],
    ['link', { rel: 'alternate', hreflang: 'en-US', href: enUrl }],
    ['link', { rel: 'alternate', hreflang: 'zh-CN', href: zhUrl }],
    ['link', { rel: 'alternate', hreflang: 'x-default', href: enUrl }],
    ['meta', { property: 'og:url', content: pageAbsoluteUrl }],
  ]
}

export default defineConfig({
  // Default site identity (English root locale).
  title:       TITLE_EN,
  description: DESCRIPTION_EN,
  lang:        'en-US',
  base:        SITE_BASE,

  // Clean URLs (no .html suffix in browser URL bar).
  cleanUrls: true,

  // Sources excluded from the VitePress build. `api-design.md` is the
  // internal v3 design contract (carries its own `doc-lint: skip-file`
  // marker for the Java-snippet linter); it is not user-facing and stays
  // out of the published site.
  srcExclude: ['api-design.md'],

  // The pre-VitePress markdown in docs/ (existing guides + migration
  // guides) contains repository-relative links to CHANGELOG.md and to
  // example READMEs that VitePress's strict dead-link checker cannot
  // resolve. D3 / D4 will rewrite those links as the content is
  // restructured into VitePress idiom; until then we accept the warnings
  // rather than fail the build. Once the cleanup lands this flag is
  // dropped and dead links become a build error again.
  ignoreDeadLinks: true,

  // Last-update timestamps in the footer (from git history) — useful for
  // documentation that pairs with versioned releases.
  lastUpdated: true,

  // Global head tags: meta tags that are identical across every page.
  // Per-page canonical / hreflang / og:url are added through transformHead.
  head: [
    ['meta', { name: 'theme-color',         content: '#3c8772' }],
    ['meta', { name: 'keywords',            content: 'jp4, P4Runtime, Java, P4, SDN, BMv2, controller, library' }],
    ['meta', { property: 'og:type',         content: 'website' }],
    ['meta', { property: 'og:site_name',    content: 'jp4' }],
    ['meta', { property: 'og:title',        content: TITLE_EN }],
    ['meta', { property: 'og:description',  content: DESCRIPTION_EN }],
    ['meta', { name: 'twitter:card',        content: 'summary' }],
    ['meta', { name: 'twitter:title',       content: TITLE_EN }],
    ['meta', { name: 'twitter:description', content: DESCRIPTION_EN }],
    ['link', { rel: 'icon',                 href: SITE_BASE + 'favicon.ico' }],
  ],

  transformHead: ({ pageData }) => pageHead(pageData),

  // Sitemap.xml generated at build time with absolute URLs.
  sitemap: {
    hostname: SITE_URL,
  },

  // Bilingual locales. The 'root' locale (English) lives at the site root
  // '/'; the 'zh' locale lives at '/zh/'. Per Constraint A the locale
  // switcher should appear in the nav so users can self-select language.
  locales: {
    root: {
      label:       'English',
      lang:        'en-US',
      title:       TITLE_EN,
      description: DESCRIPTION_EN,
      themeConfig: {
        nav: [
          { text: 'Guide',         link: '/' },
          { text: 'API reference', link: '/api/' },
          { text: 'Migrations',    link: '/migrations/' },
          { text: 'Changelog',     link: '/changelog' },
        ],
        sidebar: [],   // populated in D4/D5 when guides + cookbook land
        outline: {
          level: [2, 3],
          label: 'On this page',
        },
        editLink: {
          pattern: 'https://github.com/zhh2001/jp4/edit/main/docs/:path',
          text:    'Edit this page on GitHub',
        },
        lastUpdated: {
          text:        'Last updated',
          formatOptions: { dateStyle: 'medium' },
        },
        docFooter: {
          prev: 'Previous',
          next: 'Next',
        },
      },
    },
    zh: {
      label:       '简体中文',
      lang:        'zh-CN',
      link:        '/zh/',
      title:       TITLE_ZH,
      description: DESCRIPTION_ZH,
      themeConfig: {
        nav: [
          { text: '指南',     link: '/zh/' },
          { text: 'API 参考', link: '/zh/api/' },
          { text: '迁移指南', link: '/zh/migrations/' },
          { text: '更新日志', link: '/zh/changelog' },
        ],
        sidebar: [],   // 在 D4/D5 中填充
        outline: {
          level: [2, 3],
          label: '本页目录',
        },
        editLink: {
          pattern: 'https://github.com/zhh2001/jp4/edit/main/docs/:path',
          text:    '在 GitHub 上编辑此页',
        },
        lastUpdated: {
          text:        '最后更新',
          formatOptions: { dateStyle: 'medium' },
        },
        docFooter: {
          prev: '上一页',
          next: '下一页',
        },
      },
    },
  },

  themeConfig: {
    // Shared theme configuration (applied to all locales unless overridden
    // by the locale-specific themeConfig above).
    siteTitle:    'jp4',
    socialLinks: [
      { icon: 'github', link: 'https://github.com/zhh2001/jp4' },
    ],
    search: {
      provider: 'local',
    },
    footer: {
      message:   'Released under the Apache License 2.0.',
      copyright: '© 2026 Henghua Zhang',
    },
  },
})
