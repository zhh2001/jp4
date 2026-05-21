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
// Path prefixes (relative to docs/) that exist only in English — they have
// no `/zh/` mirror. The migration guides are EN-only by policy: they document
// a fixed past API delta and are not retranslated each release. Pages under
// these prefixes get a hreflang triple that omits the zh-CN alternate so
// search engines don't index a phantom Chinese URL that would 404.
const EN_ONLY_PREFIXES = ['migrations/']

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

  const isEnOnly = EN_ONLY_PREFIXES.some(p => relPath.startsWith(p))

  const tags: HeadConfig[] = [
    ['link', { rel: 'canonical', href: pageAbsoluteUrl }],
    ['link', { rel: 'alternate', hreflang: 'en-US', href: enUrl }],
  ]
  if (!isEnOnly) {
    tags.push(['link', { rel: 'alternate', hreflang: 'zh-CN', href: zhUrl }])
  }
  tags.push(['link', { rel: 'alternate', hreflang: 'x-default', href: enUrl }])
  tags.push(['meta', { property: 'og:url', content: pageAbsoluteUrl }])

  // Per-page keywords from frontmatter. VitePress maps frontmatter `title`
  // and `description` to the standard tags automatically, but `keywords:` is
  // not in its default mapping — without this every page would inherit the
  // global keywords list from `head:` below, which is not what Constraint A
  // (per-page unique SEO meta) wants.
  const frontmatterKeywords = pageData?.frontmatter?.keywords
  if (frontmatterKeywords) {
    const content = Array.isArray(frontmatterKeywords)
      ? frontmatterKeywords.join(', ')
      : String(frontmatterKeywords)
    tags.push(['meta', { name: 'keywords', content }])
  }

  // SoftwareSourceCode JSON-LD on the two API landing pages. Search engines
  // surface richer results when the page declares the library's metadata in
  // schema.org terms; the landing pages are the canonical entry to the
  // generated Javadoc and the right place for the structured signal.
  if (relPath === 'api/index.md' || relPath === 'zh/api/index.md') {
    const jsonLd = {
      '@context': 'https://schema.org',
      '@type': 'SoftwareSourceCode',
      name: 'jp4',
      description: 'A native Java client library for P4Runtime.',
      codeRepository: 'https://github.com/zhh2001/jp4',
      programmingLanguage: 'Java',
      runtimePlatform: 'Java 21+',
      license: 'https://www.apache.org/licenses/LICENSE-2.0',
      url: pageAbsoluteUrl,
    }
    tags.push(['script', { type: 'application/ld+json' }, JSON.stringify(jsonLd)])
  }

  return tags
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
  // `api-design.md` is the internal v3 design contract (carries its own
  // `doc-lint: skip-file` marker for the Java-snippet linter); it is not
  // user-facing and stays out of the published site.
  // `public/api/javadoc/**/*.md` excludes the legal/license markdown files
  // (dejavufonts.md / jquery.md / jqueryUI.md) that the JDK 21 Javadoc tool
  // ships alongside its HTML output — without this they get picked up by
  // VitePress's markdown scanner and leak into the sitemap with wrong URLs.
  srcExclude: ['api-design.md', 'public/api/javadoc/**/*.md'],

  // Last-update timestamps in the footer (from git history) — useful for
  // documentation that pairs with versioned releases.
  lastUpdated: true,

  // Global head tags: meta tags that are identical across every page.
  // Per-page canonical / hreflang / og:url / keywords are added through
  // transformHead — keywords specifically reads from each page's frontmatter
  // `keywords:` array.
  head: [
    ['meta', { name: 'theme-color',         content: '#3c8772' }],
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
          { text: 'Guide',         link: '/guides/getting-started' },
          { text: 'API reference', link: '/api/' },
          { text: 'Migrations',    link: '/migrations/' },
          { text: 'Changelog',     link: 'https://github.com/zhh2001/jp4/blob/main/CHANGELOG.md' },
        ],
        sidebar: [
          {
            text: 'Quick start',
            items: [
              { text: '60-second walkthrough', link: '/quickstart' },
            ],
          },
          {
            text: 'Guides',
            collapsed: false,
            items: [
              { text: 'Getting started',            link: '/guides/getting-started' },
              { text: 'Connection and arbitration', link: '/guides/connection-and-arbitration' },
              { text: 'Pipelines',                  link: '/guides/pipeline' },
              { text: 'Tables',                     link: '/guides/tables' },
              { text: 'Packet I/O',                 link: '/guides/packet-io' },
              { text: 'Error handling',             link: '/guides/error-handling' },
            ],
          },
          {
            text: 'In-depth concepts',
            collapsed: false,
            items: [
              { text: 'P4Runtime spec mapping',     link: '/concepts/p4runtime-spec-mapping' },
              { text: 'Canonical bytestring',       link: '/concepts/canonical-bytestring' },
              { text: 'port_kind idiom',            link: '/concepts/port-kind-idiom' },
              { text: 'Threading model',            link: '/concepts/threading-model' },
            ],
          },
          {
            text: 'Cookbook',
            collapsed: false,
            items: [
              { text: 'L2 learning entry installation',     link: '/cookbook/l2-learning' },
              { text: 'LPM route table batch installation', link: '/cookbook/lpm-routes' },
              { text: 'PacketIn from a secondary',          link: '/cookbook/packet-in-secondary' },
              { text: 'Inspecting multicast groups',        link: '/cookbook/multicast-group' },
              { text: 'Inspecting clone sessions',          link: '/cookbook/clone-session' },
              { text: 'Reading entity tables',              link: '/cookbook/entity-reads' },
            ],
          },
          {
            text: 'Examples',
            collapsed: false,
            items: [
              { text: 'simple-l2-switch',    link: '/examples/simple-l2-switch' },
              { text: 'simple-loadbalancer', link: '/examples/simple-loadbalancer' },
              { text: 'network-monitor',     link: '/examples/network-monitor' },
            ],
          },
          {
            text: 'Troubleshooting',
            collapsed: true,
            items: [
              { text: 'BMv2 register UNIMPLEMENTED',  link: '/troubleshooting/bmv2-register-unimplemented' },
              { text: 'Read-back bytes mismatch',     link: '/troubleshooting/canonical-bytestring-mismatch' },
              { text: 'Replica.port is null',         link: '/troubleshooting/replica-port-null' },
              { text: 'P4ArbitrationLost',            link: '/troubleshooting/arbitration-lost' },
              { text: 'No pipeline bound',            link: '/troubleshooting/no-pipeline-bound' },
              { text: 'PacketIn never fires',         link: '/troubleshooting/packet-in-not-firing' },
            ],
          },
          {
            text: 'Migration guides',
            collapsed: true,
            items: [
              { text: 'v0.1 → v1.0', link: '/migrations/migration-0.1-to-1.0' },
              { text: 'v1.0 → v1.1', link: '/migrations/migration-1.0-to-1.1' },
              { text: 'v1.1 → v1.2', link: '/migrations/migration-1.1-to-1.2' },
              { text: 'v1.2 → v1.3', link: '/migrations/migration-1.2-to-1.3' },
              { text: 'v1.3 → v1.4', link: '/migrations/migration-1.3-to-1.4' },
              { text: 'v1.4 → v1.5', link: '/migrations/migration-1.4-to-1.5' },
            ],
          },
          { text: 'API reference', link: '/api/' },
        ],
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
          { text: '指南',     link: '/zh/guides/getting-started' },
          { text: 'API 参考', link: '/zh/api/' },
          { text: '迁移指南', link: '/migrations/' },
          { text: '更新日志', link: 'https://github.com/zhh2001/jp4/blob/main/CHANGELOG.md' },
        ],
        sidebar: [
          {
            text: '快速开始',
            items: [
              { text: '60 秒上手', link: '/zh/quickstart' },
            ],
          },
          {
            text: '指南',
            collapsed: false,
            items: [
              { text: '入门',         link: '/zh/guides/getting-started' },
              { text: '连接与仲裁',   link: '/zh/guides/connection-and-arbitration' },
              { text: '流水线',       link: '/zh/guides/pipeline' },
              { text: '表',           link: '/zh/guides/tables' },
              { text: '报文 I/O',     link: '/zh/guides/packet-io' },
              { text: '错误处理',     link: '/zh/guides/error-handling' },
            ],
          },
          {
            text: '深入概念',
            collapsed: false,
            items: [
              { text: 'P4Runtime 规范映射',         link: '/zh/concepts/p4runtime-spec-mapping' },
              { text: 'Canonical bytestring 编码',  link: '/zh/concepts/canonical-bytestring' },
              { text: 'port_kind 习惯',             link: '/zh/concepts/port-kind-idiom' },
              { text: '线程模型',                   link: '/zh/concepts/threading-model' },
            ],
          },
          {
            text: 'Cookbook',
            collapsed: false,
            items: [
              { text: 'L2 学习条目安装',         link: '/zh/cookbook/l2-learning' },
              { text: 'LPM 路由表批量安装',      link: '/zh/cookbook/lpm-routes' },
              { text: '从从属控制器观察 PacketIn', link: '/zh/cookbook/packet-in-secondary' },
              { text: '查看组播组状态',          link: '/zh/cookbook/multicast-group' },
              { text: '查看克隆会话',            link: '/zh/cookbook/clone-session' },
              { text: '读取实体条目',            link: '/zh/cookbook/entity-reads' },
            ],
          },
          {
            text: '示例',
            collapsed: false,
            items: [
              { text: 'simple-l2-switch',    link: '/zh/examples/simple-l2-switch' },
              { text: 'simple-loadbalancer', link: '/zh/examples/simple-loadbalancer' },
              { text: 'network-monitor',     link: '/zh/examples/network-monitor' },
            ],
          },
          {
            text: '故障排查',
            collapsed: true,
            items: [
              { text: 'BMv2 register UNIMPLEMENTED', link: '/zh/troubleshooting/bmv2-register-unimplemented' },
              { text: '读回字节不一致',              link: '/zh/troubleshooting/canonical-bytestring-mismatch' },
              { text: 'Replica.port 为 null',        link: '/zh/troubleshooting/replica-port-null' },
              { text: 'P4ArbitrationLost',           link: '/zh/troubleshooting/arbitration-lost' },
              { text: '流水线未绑定',                link: '/zh/troubleshooting/no-pipeline-bound' },
              { text: 'PacketIn 从未触发',           link: '/zh/troubleshooting/packet-in-not-firing' },
            ],
          },
          {
            text: '迁移指南',
            collapsed: true,
            items: [
              { text: 'v0.1 → v1.0', link: '/migrations/migration-0.1-to-1.0' },
              { text: 'v1.0 → v1.1', link: '/migrations/migration-1.0-to-1.1' },
              { text: 'v1.1 → v1.2', link: '/migrations/migration-1.1-to-1.2' },
              { text: 'v1.2 → v1.3', link: '/migrations/migration-1.2-to-1.3' },
              { text: 'v1.3 → v1.4', link: '/migrations/migration-1.3-to-1.4' },
              { text: 'v1.4 → v1.5', link: '/migrations/migration-1.4-to-1.5' },
            ],
          },
          { text: 'API 参考', link: '/zh/api/' },
        ],
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
