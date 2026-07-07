// Links externos das plataformas Stavias; ajustar URLs conforme o ambiente.
const STAVIAS_LINKS: { label: string; href: string }[] = [
  {
    label: "Portal Stavias",
    href: "https://www.stavias.com.br",
  },
  {
    label: "Stavias Academy",
    href: "https://academy.stavias.com.br",
  },
  {
    label: "Central de Suporte",
    href: "https://suporte.stavias.com.br",
  },
];

export function MaisStaviasCard() {
  return (
    <section className="home-card home-card--brand">
      <h3>Mais Stavias</h3>
      <ul>
        {STAVIAS_LINKS.map((link) => (
          <li key={link.href}>
            <a
              href={link.href}
              target="_blank"
              rel="noreferrer"
            >
              ↗ {link.label}
            </a>
          </li>
        ))}
      </ul>
    </section>
  );
}
