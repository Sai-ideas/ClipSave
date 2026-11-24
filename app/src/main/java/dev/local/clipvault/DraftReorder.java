package dev.local.clipvault;

import android.graphics.drawable.GradientDrawable;
import android.view.DragEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

final class DraftReorder {
    private final ScrollView scroll;
    private final View page;
    private final BooleanSupplier canStart;
    private final BiConsumer<Integer, Integer> move;
    private final ArrayList<View> cards = new ArrayList<>();
    private final int[] scrollLocation = new int[2];
    private final int[] scopeLocation = new int[2];
    private final GradientDrawable insertionLine = new GradientDrawable();
    private final int slop;
    private final int edge;
    private final int step;
    private final int halfGap;
    private final int lineHeight;
    private int origin = -1;
    private int target = -1;
    private float pointerX;
    private float pointerY;
    private final Runnable autoScroll = this::scrollTowardPointer;

    private record Drag(DraftReorder owner, int origin) {}

    DraftReorder(ScrollView scroll, View page, BooleanSupplier canStart, BiConsumer<Integer, Integer> move,
                 int cardGap, int accent) {
        this.page = page;
        this.scroll = scroll;
        this.canStart = canStart;
        this.move = move;
        float density = page.getResources().getDisplayMetrics().density;
        edge = Math.round(density * 60);
        step = Math.round(density * 9);
        halfGap = cardGap / 2;
        lineHeight = Math.round(density * 5);
        insertionLine.setColor(accent);
        insertionLine.setCornerRadius(lineHeight / 2f);
        slop = ViewConfiguration.get(scroll.getContext()).getScaledTouchSlop();
    }

    static void routeEvents(ScrollView scroll) {
        scroll.setOnDragListener((view, event) -> event.getLocalState() instanceof Drag drag
                && drag.owner().scroll == scroll && drag.owner().handleDrag(event, drag));
    }

    void addCard(View card, View handle) {
        int index = cards.size();
        cards.add(card);
        handle.setOnTouchListener(new View.OnTouchListener() {
            private float startX;
            private float startY;

            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> {
                        startX = event.getX();
                        startY = event.getY();
                        if (cards.size() > 1 && canStart.getAsBoolean()) {
                            view.getParent().requestDisallowInterceptTouchEvent(true);
                        }
                    }
                    case MotionEvent.ACTION_MOVE -> {
                        if (cards.size() > 1 && canStart.getAsBoolean()
                                && Math.abs(event.getY() - startY) > slop
                                && Math.abs(event.getY() - startY) >= Math.abs(event.getX() - startX)) {
                            view.setPressed(false);
                            return view.startDragAndDrop(null, new View.DragShadowBuilder(handle),
                                    new Drag(DraftReorder.this, index), 0);
                        }
                    }
                    case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                            view.getParent().requestDisallowInterceptTouchEvent(false);
                    default -> {}
                }
                return false;
            }
        });
    }

    private boolean handleDrag(DragEvent event, Drag drag) {
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED -> {
                origin = drag.origin();
                cards.get(origin).setAlpha(0.55f);
            }
            case DragEvent.ACTION_DRAG_LOCATION -> {
                pointerX = event.getX();
                pointerY = event.getY();
                showInsertionLine(slotAt(pointerX, pointerY));
                scroll.removeCallbacks(autoScroll);
                if (scrollDirection() != 0) scroll.postOnAnimation(autoScroll);
            }
            case DragEvent.ACTION_DRAG_EXITED -> {
                scroll.removeCallbacks(autoScroll);
                showInsertionLine(-1);
            }
            case DragEvent.ACTION_DROP -> {
                int slot = slotAt(event.getX(), event.getY());
                scroll.removeCallbacks(autoScroll);
                showInsertionLine(-1);
                if (slot < 0) return false;
                // Removing the source shifts every slot after it one position earlier.
                int destination = slot <= origin ? slot : slot - 1;
                if (origin >= 0 && origin != destination && canStart.getAsBoolean()) move.accept(origin, destination);
            }
            case DragEvent.ACTION_DRAG_ENDED -> {
                scroll.removeCallbacks(autoScroll);
                showInsertionLine(-1);
                if (origin >= 0) cards.get(origin).setAlpha(1f);
                origin = -1;
            }
            default -> {}
        }
        return true;
    }

    private int slotAt(float x, float y) {
        scroll.getLocationInWindow(scrollLocation);
        page.getLocationInWindow(scopeLocation);
        float pageX = x + scrollLocation[0] - scopeLocation[0];
        float pageY = y + scrollLocation[1] - scopeLocation[1];
        View first = cards.get(0);
        View last = cards.get(cards.size() - 1);
        if (pageX < first.getLeft() || pageX >= first.getRight()
                || pageY < first.getTop() - halfGap || pageY > last.getBottom() + halfGap) return -1;
        for (int index = 0; index < cards.size(); index++) {
            View card = cards.get(index);
            if (pageY < card.getTop() + card.getHeight() / 2f) return index;
        }
        return cards.size();
    }

    private int scrollDirection() {
        int threshold = Math.min(edge, scroll.getHeight() / 3);
        if (pointerY < threshold && scroll.canScrollVertically(-1)) return -1;
        if (pointerY > scroll.getHeight() - threshold && scroll.canScrollVertically(1)) return 1;
        return 0;
    }

    private void scrollTowardPointer() {
        int direction = scrollDirection();
        if (origin < 0 || direction == 0 || !scroll.isAttachedToWindow()) return;
        scroll.scrollBy(0, step * direction);
        showInsertionLine(slotAt(pointerX, pointerY));
        if (scrollDirection() != 0) scroll.postOnAnimation(autoScroll);
    }

    private void showInsertionLine(int slot) {
        if (slot == target) return;
        page.getOverlay().remove(insertionLine);
        target = slot;
        if (slot < 0 || slot == origin || slot == origin + 1) return;

        View before = slot == 0 ? null : cards.get(slot - 1);
        View after = slot == cards.size() ? null : cards.get(slot);
        int center;
        if (before == null) center = after.getTop() - halfGap;
        else if (after == null) center = before.getBottom() + halfGap;
        else center = (before.getBottom() + after.getTop()) / 2;
        int top = center - lineHeight / 2;
        View first = cards.get(0);
        insertionLine.setBounds(first.getLeft(), top, first.getRight(), top + lineHeight);
        page.getOverlay().add(insertionLine);
    }
}
