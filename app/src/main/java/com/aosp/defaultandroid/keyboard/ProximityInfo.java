/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.aosp.defaultandroid.keyboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProximityInfo {
    // Must be equal to MAX_PROXIMITY_CHARS_SIZE in native/jni/src/defines.h
    /** Number of key widths from current touch point to search for nearest keys. */
    private static final float SEARCH_DISTANCE = 1.2f;
    private static final List<Key> EMPTY_KEY_LIST = Collections.emptyList();

    private final int mGridWidth;
    private final int mGridHeight;
    private final int mGridSize;
    private final int mCellWidth;
    private final int mCellHeight;
    // TODO: Find a proper name for mKeyboardMinWidth
    private final int mKeyboardMinWidth;
    private final int mKeyboardHeight;
    private final int mMostCommonKeyWidth;
    private final List<Key> mSortedKeys;
    private final List<Key>[] mGridNeighbors;

    @SuppressWarnings("unchecked")
    ProximityInfo(final int gridWidth, final int gridHeight, final int minWidth, final int height,
            final int mostCommonKeyWidth,
            final List<Key> sortedKeys) {
        mGridWidth = gridWidth;
        mGridHeight = gridHeight;
        mGridSize = mGridWidth * mGridHeight;
        mCellWidth = (minWidth + mGridWidth - 1) / mGridWidth;
        mCellHeight = (height + mGridHeight - 1) / mGridHeight;
        mKeyboardMinWidth = minWidth;
        mKeyboardHeight = height;
        mMostCommonKeyWidth = mostCommonKeyWidth;
        mSortedKeys = sortedKeys;
        mGridNeighbors = new List[mGridSize];
        if (minWidth == 0 || height == 0) {
            // No proximity required. Keyboard might be more keys keyboard.
            return;
        }
        computeNearestNeighbors();
    }

    private void computeNearestNeighbors() {
        final int defaultWidth = mMostCommonKeyWidth;
        final int keyCount = mSortedKeys.size();
        final int gridSize = mGridNeighbors.length;
        final int threshold = (int) (defaultWidth * SEARCH_DISTANCE);
        final int thresholdSquared = threshold * threshold;
        // Round-up so we don't have any pixels outside the grid
        final int lastPixelXCoordinate = mGridWidth * mCellWidth - 1;
        final int lastPixelYCoordinate = mGridHeight * mCellHeight - 1;

        // For large layouts, 'neighborsFlatBuffer' is about 80k of memory: gridSize is usually 512,
        // keycount is about 40 and a pointer to a Key is 4 bytes. This contains, for each cell,
        // enough space for as many keys as there are on the keyboard. Hence, every
        // keycount'th element is the start of a new cell, and each of these virtual subarrays
        // start empty with keycount spaces available. This fills up gradually in the loop below.
        // Since in the practice each cell does not have a lot of neighbors, most of this space is
        // actually just empty padding in this fixed-size buffer.
        final Key[] neighborsFlatBuffer = new Key[gridSize * keyCount];
        final int[] neighborCountPerCell = new int[gridSize];
        final int halfCellWidth = mCellWidth / 2;
        final int halfCellHeight = mCellHeight / 2;
        for (final Key key : mSortedKeys) {
            if (key.isSpacer()) continue;

/* HOW WE PRE-SELECT THE CELLS (iterate over only the relevant cells, instead of all of them)

  We want to compute the distance for keys that are in the cells that are close enough to the
  key border, as this method is performance-critical. These keys are represented with 'star'
  background on the diagram below. Let's consider the Y case first.

  We want to select the cells which center falls between the top of the key minus the threshold,
  and the bottom of the key plus the threshold.
  topPixelWithinThreshold is key.mY - threshold, and bottomPixelWithinThreshold is
  key.mY + key.mHeight + threshold.

  Then we need to compute the center of the top row that we need to evaluate, as we'll iterate
  from there.

(0,0)----> x
| .-------------------------------------------.
| |   |   |   |   |   |   |   |   |   |   |   |
| |---+---+---+---+---+---+---+---+---+---+---|   .- top of top cell (aligned on the grid)
| |   |   |   |   |   |   |   |   |   |   |   |   |
| |-----------+---+---+---+---+---+---+---+---|---'                          v
| |   |   |   |***|***|*_________________________ topPixelWithinThreshold    | yDeltaToGrid
| |---+---+---+-----^-+-|-+---+---+---+---+---|                              ^
| |   |   |   |***|*|*|*|*|***|***|   |   |   |           ______________________________________
v |---+---+--threshold--|-+---+---+---+---+---|          |
  |   |   |   |***|*|*|*|*|***|***|   |   |   |          | Starting from key.mY, we substract
y |---+---+---+---+-v-+-|-+---+---+---+---+---|          | thresholdBase and get the top pixel
  |   |   |   |***|**########------------------- key.mY  | within the threshold. We align that on
  |---+---+---+---+--#+---+-#-+---+---+---+---|          | the grid by computing the delta to the
  |   |   |   |***|**#|***|*#*|***|   |   |   |          | grid, and get the top of the top cell.
  |---+---+---+---+--#+---+-#-+---+---+---+---|          |
  |   |   |   |***|**########*|***|   |   |   |          | Adding half the cell height to the top
  |---+---+---+---+---+-|-+---+---+---+---+---|          | of the top cell, we get the middle of
  |   |   |   |***|***|*|*|***|***|   |   |   |          | the top cell (yMiddleOfTopCell).
  |---+---+---+---+---+-|-+---+---+---+---+---|          |
  |   |   |   |***|***|*|*|***|***|   |   |   |          |
  |---+---+---+---+---+-|________________________ yEnd   | Since we only want to add the key to
  |   |   |   |   |   |   | (bottomPixelWithinThreshold) | the proximity if it's close enough to
  |---+---+---+---+---+---+---+---+---+---+---|          | the center of the cell, we only need
  |   |   |   |   |   |   |   |   |   |   |   |          | to compute for these cells where
  '---'---'---'---'---'---'---'---'---'---'---'          | topPixelWithinThreshold is above the
                                        (positive x,y)   | center of the cell. This is the case
                                                         | when yDeltaToGrid is less than half
  [Zoomed in diagram]                                    | the height of the cell.
  +-------+-------+-------+-------+-------+              |
  |       |       |       |       |       |              | On the zoomed in diagram, on the right
  |       |       |       |       |       |              | the topPixelWithinThreshold (represented
  |       |       |       |       |       |      top of  | with an = sign) is below and we can skip
  +-------+-------+-------+--v----+-------+ .. top cell  | this cell, while on the left it's above
  |       | = topPixelWT  |  |  yDeltaToGrid             | and we need to compute for this cell.
  |..yStart.|.....|.......|..|....|.......|... y middle  | Thus, if yDeltaToGrid is more than half
  |   (left)|     |       |  ^ =  |       | of top cell  | the height of the cell, we start the
  +-------+-|-----+-------+----|--+-------+              | iteration one cell below the top cell,
  |       | |     |       |    |  |       |              | else we start it on the top cell. This
  |.......|.|.....|.......|....|..|.....yStart (right)   | is stored in yStart.

  Since we only want to go up to bottomPixelWithinThreshold, and we only iterate on the center
  of the keys, we can stop as soon as the y value exceeds bottomPixelThreshold, so we don't
  have to align this on the center of the key. Hence, we don't need a separate value for
  bottomPixelWithinThreshold and call this yEnd right away.
*/
            final int keyX = key.getX();
            final int keyY = key.getY();
            final int topPixelWithinThreshold = keyY - threshold;
            final int yDeltaToGrid = topPixelWithinThreshold % mCellHeight;
            final int yMiddleOfTopCell = topPixelWithinThreshold - yDeltaToGrid + halfCellHeight;
            final int yStart = Math.max(halfCellHeight,
                    yMiddleOfTopCell + (yDeltaToGrid <= halfCellHeight ? 0 : mCellHeight));
            final int yEnd = Math.min(lastPixelYCoordinate, keyY + key.getHeight() + threshold);

            final int leftPixelWithinThreshold = keyX - threshold;
            final int xDeltaToGrid = leftPixelWithinThreshold % mCellWidth;
            final int xMiddleOfLeftCell = leftPixelWithinThreshold - xDeltaToGrid + halfCellWidth;
            final int xStart = Math.max(halfCellWidth,
                    xMiddleOfLeftCell + (xDeltaToGrid <= halfCellWidth ? 0 : mCellWidth));
            final int xEnd = Math.min(lastPixelXCoordinate, keyX + key.getWidth() + threshold);

            int baseIndexOfCurrentRow = (yStart / mCellHeight) * mGridWidth + (xStart / mCellWidth);
            for (int centerY = yStart; centerY <= yEnd; centerY += mCellHeight) {
                int index = baseIndexOfCurrentRow;
                for (int centerX = xStart; centerX <= xEnd; centerX += mCellWidth) {
                    if (key.squaredDistanceToEdge(centerX, centerY) < thresholdSquared) {
                        neighborsFlatBuffer[index * keyCount + neighborCountPerCell[index]] = key;
                        ++neighborCountPerCell[index];
                    }
                    ++index;
                }
                baseIndexOfCurrentRow += mGridWidth;
            }
        }

        for (int i = 0; i < gridSize; ++i) {
            final int indexStart = i * keyCount;
            final int indexEnd = indexStart + neighborCountPerCell[i];
            final ArrayList<Key> neighbors = new ArrayList<>(indexEnd - indexStart);
            for (int index = indexStart; index < indexEnd; index++) {
                neighbors.add(neighborsFlatBuffer[index]);
            }
            mGridNeighbors[i] = Collections.unmodifiableList(neighbors);
        }
    }

    public List<Key> getNearestKeys(final int x, final int y) {
        if (x >= 0 && x < mKeyboardMinWidth && y >= 0 && y < mKeyboardHeight) {
            int index = (y / mCellHeight) * mGridWidth + (x / mCellWidth);
            if (index < mGridSize) {
                return mGridNeighbors[index];
            }
        }
        return EMPTY_KEY_LIST;
    }
}
