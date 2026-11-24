package dev.local.clipvault;

import android.graphics.Rect;
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
    private final BooleanSupplier canStart;
    private final BiConsumer<Integer, Integer> move;
    private final ArrayList<View> cards = new ArrayList<>();
    private final Rect bounds = new Rect();
    private final int slop;
    private final int edge;
    private final int step;
    private int origin = -1;
    private int target = -1;
    private float pointerX;
    private float pointerY;
    private final Runnable autoScroll = this::scrollTowardPointer;

    private record Drag(DraftReorder owner, int origin) {}

    DraftReorder(ScrollView scroll, BooleanSupplier canStart, BiConsumer<Integer, Integer> move,
                 int edge, int step) {
        this.scroll = scroll;
        this.canStart = canStart;
        this.move = move;
        this.edge = edge;
        this.step = step;
        slop = ViewConfiguration.get(scroll.getContext()).getScaledTouchSlop();
        scroll.setOnDragListener((view, event) -> handleDrag(event));
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

    private boolean handleDrag(DragEvent event) {
        if (!(event.getLocalState() instanceof Drag drag) || drag.owner() != this) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED -> origin = drag.origin();
            case DragEvent.ACTION_DRAG_LOCATION -> {
                pointerX = event.getX();
                pointerY = event.getY();
                highlight(targetAt(pointerX, pointerY));
                scroll.removeCallbacks(autoScroll);
                if (scrollDirection() != 0) scroll.postOnAnimation(autoScroll);
            }
            case DragEvent.ACTION_DRAG_EXITED -> {
                scroll.removeCallbacks(autoScroll);
                highlight(-1);
            }
            case DragEvent.ACTION_DROP -> {
                int destination = targetAt(event.getX(), event.getY());
                scroll.removeCallbacks(autoScroll);
                highlight(-1);
                if (destination < 0) return false;
                if (origin >= 0 && origin != destination && canStart.getAsBoolean()) move.accept(origin, destination);
            }
            case DragEvent.ACTION_DRAG_ENDED -> {
                scroll.removeCallbacks(autoScroll);
                highlight(-1);
                origin = -1;
            }
            default -> {}
        }
        return true;
    }

    private int targetAt(float x, float y) {
        int contentY = (int) y + scroll.getScrollY();
        for (int index = 0; index < cards.size(); index++) {
            View card = cards.get(index);
            bounds.set(0, 0, card.getWidth(), card.getHeight());
            scroll.offsetDescendantRectToMyCoords(card, bounds);
            if (bounds.contains((int) x, contentY)) return index;
        }
        return -1;
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
        highlight(targetAt(pointerX, pointerY));
        if (scrollDirection() != 0) scroll.postOnAnimation(autoScroll);
    }

    private void highlight(int index) {
        if (index == target) return;
        if (target >= 0) cards.get(target).setAlpha(1f);
        target = index;
        if (target >= 0 && target != origin) cards.get(target).setAlpha(0.65f);
    }
}
