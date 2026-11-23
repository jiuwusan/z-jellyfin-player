/* Mouse interaction for design previews only; touch scrolling stays native. */
(() => {
  'use strict';
  const threshold = 8;
  const blockedClicks = new WeakMap();
  let gesture = null;

  const railFor = target => target instanceof Element ? target.closest('.rail') : null;

  function snap(rail) {
    const padding = parseFloat(getComputedStyle(rail).paddingLeft) || 0;
    const left = rail.getBoundingClientRect().left;
    const current = rail.scrollLeft;
    const maximum = Math.max(0, rail.scrollWidth - rail.clientWidth);
    const stops = [...rail.querySelectorAll(':scope > .card')].map(card =>
      Math.max(0, Math.min(maximum, current + card.getBoundingClientRect().left - left - padding)));
    if (!stops.length) return;
    const closest = stops.reduce((best, next) =>
      Math.abs(next - current) < Math.abs(best - current) ? next : best);
    rail.scrollTo({ left: closest, behavior: 'instant' });
  }

  function finish(event) {
    const active = gesture;
    if (!active || event && event.pointerId !== active.pointerId) return;
    gesture = null;
    if (active.dragged) {
      if (event && event.cancelable) event.preventDefault();
      blockedClicks.set(active.rail, performance.now() + 600);
      active.rail.classList.remove('is-dragging');
      if (active.rail.isConnected) snap(active.rail);
    }
    if (active.rail.hasPointerCapture(active.pointerId)) {
      active.rail.releasePointerCapture(active.pointerId);
    }
  }

  document.addEventListener('pointerdown', event => {
    if (event.pointerType !== 'mouse' || event.button !== 0 || !event.isPrimary) return;
    const rail = railFor(event.target);
    if (!rail || rail.scrollWidth <= rail.clientWidth) return;
    finish();
    blockedClicks.delete(rail);
    gesture = { rail, pointerId: event.pointerId, x: event.clientX, y: event.clientY,
      scroll: rail.scrollLeft, dragged: false };
  });

  window.addEventListener('pointermove', event => {
    const active = gesture;
    if (!active || active.pointerId !== event.pointerId) return;
    const dx = event.clientX - active.x;
    const dy = event.clientY - active.y;
    if (!active.dragged) {
      if (Math.abs(dy) > threshold && Math.abs(dy) > Math.abs(dx)) {
        gesture = null;
        return;
      }
      if (Math.abs(dx) < threshold || Math.abs(dx) < Math.abs(dy)) return;
      active.dragged = true;
      active.rail.classList.add('is-dragging');
      try { active.rail.setPointerCapture(event.pointerId); } catch { /* Synthetic preview checks. */ }
      getSelection()?.removeAllRanges();
    }
    event.preventDefault();
    active.rail.scrollLeft = active.scroll - dx;
  }, { passive: false });

  window.addEventListener('pointerup', finish);
  window.addEventListener('pointercancel', finish);
  document.addEventListener('lostpointercapture', finish);
  window.addEventListener('blur', () => finish());

  document.addEventListener('click', event => {
    const rail = railFor(event.target);
    if (!rail || event.detail === 0 || (blockedClicks.get(rail) || 0) < performance.now()) return;
    blockedClicks.delete(rail);
    event.preventDefault();
    event.stopImmediatePropagation();
  }, true);

  document.addEventListener('dragstart', event => {
    if (railFor(event.target)) event.preventDefault();
  });
})();
