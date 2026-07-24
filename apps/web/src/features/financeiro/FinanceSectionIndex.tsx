import {
  FINANCE_SECTION_GROUPS,
  FINANCE_SECTIONS,
  type ActiveFinanceSection,
} from "./financeSectionIndexModel";

export function FinanceSectionIndex({
  activeSection,
  onSelect,
}: {
  activeSection: ActiveFinanceSection;
  onSelect: (section: ActiveFinanceSection) => void;
}) {
  return (
    <nav
      className="finance-module-index"
      aria-label="Áreas do Financeiro"
    >
      {FINANCE_SECTION_GROUPS.map((group) => (
        <div className="finance-module-index__group" key={group}>
          <span className="finance-module-index__label">{group}</span>
          <div>
            {FINANCE_SECTIONS
              .filter((section) => section.group === group)
              .map((section) => {
                const active = section.id === activeSection;
                return (
                  <button
                    key={section.id}
                    type="button"
                    aria-current={active ? "page" : undefined}
                    className={active ? "is-active" : ""}
                    onClick={() => onSelect(section.id)}
                  >
                    {section.label}
                  </button>
                );
              })}
          </div>
        </div>
      ))}
    </nav>
  );
}
