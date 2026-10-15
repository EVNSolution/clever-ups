import type { JSX } from "preact";
import { useCallback, useEffect, useState } from "preact/hooks";
import { addDays, addMonths, isDay, monthGrid, monthOf, today } from "../api/day";
import { Icon } from "./Icon";
import { Popover } from "./Popover";

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"] as const;

const label = (day: string): string => {
  const [year, month, date] = day.split("-").map(Number);
  return `${year}년 ${month}월 ${date}일`;
};

/**
 * The operating day. The year, the month and the day are one button: a click on any of them opens the
 * calendar. The calendar is ours, drawn with the page's tokens, and works from the keyboard:
 * arrows move a day or a week, Page Up and Page Down a month, Enter chooses, Escape closes.
 * Next to it: the day before, the day after, and today.
 */
export function DayPicker(props: { day: string; onChange: (day: string) => void }): JSX.Element {
  const { day, onChange } = props;
  const [open, setOpen] = useState(false);
  const [focused, setFocused] = useState(day);
  const isToday = day === today();
  const [year, month, date] = day.split("-");

  const show = useCallback(() => {
    setFocused(day);
    setOpen(true);
  }, [day]);
  const close = useCallback((restoreFocus: boolean) => {
    setOpen(false);
    if (restoreFocus) document.getElementById("day-trigger")?.focus();
  }, []);
  const dismiss = useCallback(() => close(false), [close]);

  const choose = (next: string): void => {
    if (isDay(next)) onChange(next);
    close(true);
  };

  // Keep the keyboard focus on the focused day when it moves, also into another month.
  useEffect(() => {
    if (open) document.querySelector<HTMLElement>(`[data-day="${focused}"]`)?.focus();
  }, [open, focused]);

  const onKeyDown = (event: KeyboardEvent): void => {
    const moves: Record<string, string> = {
      ArrowLeft: addDays(focused, -1),
      ArrowRight: addDays(focused, 1),
      ArrowUp: addDays(focused, -7),
      ArrowDown: addDays(focused, 7),
      PageUp: addMonths(focused, -1),
      PageDown: addMonths(focused, 1),
    };
    const target = moves[event.key];
    if (target !== undefined) {
      event.preventDefault();
      setFocused(target);
    } else if (event.key === "Escape") {
      event.preventDefault();
      close(true);
    }
  };

  const grid = monthGrid(focused);
  const weeks = Array.from({ length: 6 }, (_, row) => grid.slice(row * 7, row * 7 + 7));
  const viewMonth = monthOf(focused);
  const [viewYear, viewMonthNumber] = viewMonth.split("-").map(Number);

  return (
    <div class="day-picker">
      <button type="button" class="icon-btn" aria-label="전날" onClick={() => onChange(addDays(day, -1))}>
        <Icon name="chevron-left" size={16} />
      </button>
      <Popover
        open={open}
        onClose={dismiss}
        focusOnOpen={`[data-day="${focused}"]`}
        trigger={
          <button
            id="day-trigger"
            type="button"
            class="day-trigger"
            aria-haspopup="true"
            aria-expanded={open}
            aria-label={`운영일 ${label(day)}, 달력 열기`}
            onClick={() => (open ? close(false) : show())}
          >
            <span class="day-part">{year}</span>
            <span class="day-dot">.</span>
            <span class="day-part">{month}</span>
            <span class="day-dot">.</span>
            <span class="day-part">{date}</span>
            <Icon name="calendar" size={16} />
          </button>
        }
      >
        <section class="calendar" aria-label="운영일 선택">
          <div class="calendar-head">
            <button
              type="button"
              class="icon-btn"
              aria-label="이전 달"
              onKeyDown={onKeyDown}
              onClick={() => setFocused(addMonths(focused, -1))}
            >
              <Icon name="chevron-left" size={16} />
            </button>
            <strong aria-live="polite">{`${viewYear}년 ${viewMonthNumber}월`}</strong>
            <button
              type="button"
              class="icon-btn"
              aria-label="다음 달"
              onKeyDown={onKeyDown}
              onClick={() => setFocused(addMonths(focused, 1))}
            >
              <Icon name="chevron-right" size={16} />
            </button>
          </div>
          <table class="calendar-grid">
            <caption class="sr-only">{`${viewYear}년 ${viewMonthNumber}월`}</caption>
            <thead>
              <tr>
                {WEEKDAYS.map(name => (
                  <th key={name} scope="col" class="calendar-weekday">
                    {name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {weeks.map(week => (
                <tr key={week[0]}>
                  {week.map(cell => (
                    <td key={cell}>
                      <button
                        type="button"
                        class="calendar-day"
                        data-day={cell}
                        data-outside={monthOf(cell) !== viewMonth}
                        data-selected={cell === day}
                        data-today={cell === today()}
                        aria-label={label(cell)}
                        aria-current={cell === today() ? "date" : undefined}
                        aria-pressed={cell === day}
                        tabIndex={cell === focused ? 0 : -1}
                        onKeyDown={onKeyDown}
                        onClick={() => choose(cell)}
                      >
                        {Number(cell.slice(8))}
                      </button>
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
          <div class="calendar-foot">
            <button type="button" class="btn btn-soft" disabled={isToday} onClick={() => choose(today())}>
              오늘
            </button>
          </div>
        </section>
      </Popover>
      <button type="button" class="icon-btn" aria-label="다음날" onClick={() => onChange(addDays(day, 1))}>
        <Icon name="chevron-right" size={16} />
      </button>
    </div>
  );
}
