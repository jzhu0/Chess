package chess.bots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

import chess.board.*;
import cse332.chess.interfaces.AbstractSearcher;
import cse332.chess.interfaces.Board;
import cse332.chess.interfaces.Evaluator;
import cse332.chess.interfaces.Move;
import chess.board.ArrayBoard;
import java.util.HashMap;

public class ModifiedJamboreeSearcher<M extends Move<M>, B extends Board<M, B>> extends
        AbstractSearcher<M, B> {
    
    private static final ForkJoinPool POOL = new ForkJoinPool(32);
    private static final double PERCENTAGE_SEQUENTIAL = 0.5;
    // protected int ply
    // protected int cutoff
    protected int divideCutoff = 2;

    protected static HashMap<Long, Tuple> tt = new HashMap<Long, Tuple>();
    private static final int MAX_TT_SIZE = 524288;
        // some arbitrary number idk - this is  2^19
    
    protected static final int SORT_PLY = 2;
    protected static final int SORT_CUTOFF = 1;
    protected static final int SORT_DIVCUTOFF = 2;
    
    public M getBestMove(B board, int myTime, int opTime) {
        /* Calculate the best move */
        int depth = this.ply;
        int cut = this.cutoff;
        if (myTime != 0) {
            if (myTime < 30000) {
                cut--;
                depth-=2;
            } else if (myTime < 90000) {
                depth--;
            }
        }
        
        int numPieces = ((ArrayBoard)board).countOfAllPieces();
        if (numPieces <= 7) {
            cut++;
            depth+=2;
        } else if (numPieces <= 18) {
            depth++;
        }
        
        BestMove<M> best = jamboree(this.evaluator, board, depth, cut, this.divideCutoff,
                (0 - this.evaluator.infty()), this.evaluator.infty() );
        System.err.println(best.value + ", " + best.move);
        reportNewBestMove(best.move);
        return best.move;
    }

    // S: will this ever return null?
    static <M extends Move<M>, B extends Board<M, B>> BestMove<M> jamboree
            (Evaluator<B> evaluator, B board, int depth, int cutoff, int divideCutoff,
                    int alpha, int beta) {
        
        List<M> moves = board.generateMoves();
        BestMove<M> best = POOL.invoke(new AlphaBetaTask<M, B>(evaluator, board, depth, moves, null,
                0, moves.size(), cutoff, divideCutoff, alpha, beta));
        
        return best;
    }

    protected static synchronized void ttInsert(Long sig, Integer value, Integer depth) {
        if (tt.containsKey(sig)) {
            if (depth < tt.get(sig).depth) {
                return;
            } else if (depth == tt.get(sig).depth && value <= tt.get(sig).value) {
                return;
            }
        } else if (tt.size() >= MAX_TT_SIZE) {
            Long randB = null;
            for (Long key : tt.keySet()) {
                randB = key;
                break;
            }
            tt.remove(randB);
        }
        tt.put(sig, new Tuple(value, depth));
    }
    
    protected static class Tuple {
        Integer value;
        Integer depth;
        
        public Tuple(Integer value, Integer depth) {
            this.value = value;
            this.depth = depth;
        }
    }
    
    private static class AlphaBetaTask<M extends Move<M>, B extends Board<M, B>> extends RecursiveTask<BestMove<M>> {
        
        Evaluator<B> eval;
        B board;
        int depth;
        List<M> moves;
        M move;
        int start, end;
        int cutoff, divideCutoff;
        int alpha, beta;
        
        public AlphaBetaTask(Evaluator<B> eval, B board, int depth, List<M> moves, M move,
                int start, int end, int cutoff, int divideCutoff, int alpha, int beta) {
            this.eval = eval;
            this.board = board; //.copy();
            this.depth = depth;
            this.moves = moves;
            this.move = move;
            this.start = start;
            this.end = end;
            this.cutoff = cutoff;
            this.divideCutoff = divideCutoff;
            this.alpha = alpha;
            this.beta = beta;
        }
        
        
        @Override
        protected BestMove<M> compute() {
            
            BestMove<M> curMove = null;
            if (move != null) {
                // move should be null unless we hit case 2 and are recursing
                //  --> copy board, apply move, get new set of moves, and reset start/end
                this.board = board.copy();
                this.board.applyMove(move);
                if (tt.containsKey(this.board.signature())
                        && tt.get(this.board.signature()).depth >= this.depth) {
                    curMove = new BestMove<M>(tt.get(this.board.signature()).value);
                    curMove.negate();
                    if (curMove.value > this.alpha) {
                        this.alpha = curMove.value;
                    }
                }
                this.moves = board.generateMoves();
                this.start = 0;
                this.end = this.moves.size();
            }
            
            // base cases: either a leaf or depth == 0
            // leaves are either mate or stalemate
            /*
            if (depth == 0) {
                return new BestMove<M>(eval.eval(board));
            }
            */
            if (depth <= cutoff) {
                // do sequentially using SimpleSearcher
                return AlphaBetaSearcher.alphabeta(eval, board, depth, alpha, beta);
            }
            
            if (move == null) {
                this.moves = board.generateMoves();
            }
            sortMoves();
            
            if (moves.isEmpty()) {
                if (board.inCheck()) {
                    return new BestMove<M>(0 - eval.mate() - depth);
                } else {
                    return new BestMove<M>(0 - eval.stalemate());
                }
            }
            
            BestMove<M> best = new BestMove<M>(alpha);

            for (int i = 0; i < (int)(PERCENTAGE_SEQUENTIAL * moves.size()); i++) {
                M mv = moves.get(i);
                board.applyMove(mv);
                
                BestMove<M> move = new BestMove<M>(0 - eval.infty());
                if (tt.containsKey(this.board.signature())
                        && tt.get(this.board.signature()).depth >= this.depth) {
                    move = new BestMove<M>(tt.get(this.board.signature()).value);
//                    System.err.print(depth);
//                    System.err.println("ss: " + board.signature() + "," + move.value + "," + tt.get(this.board.signature()).depth);
                } else {
                    AlphaBetaTask<M, B> fork = new AlphaBetaTask<M, B>(eval, board, depth-1, moves,
                            null, start, end, cutoff, divideCutoff, -beta, -alpha);
                    //BestMove<M> move = jamboree(eval, board, depth-1, cutoff, divideCutoff, -beta, -alpha);
                    move = fork.compute();
                }
                move.negate();
                board.undoMove();
                
                if (move.value > alpha) {
                    alpha = move.value;
                    best = move;
                    best.move = mv;
                }
                
                if (alpha >= beta) {
                    if (curMove != null && curMove.value == alpha) {
                        best.move = this.move;
                    }
                    return best;
                } else if (this.depth >= 4) {
                    ttInsert(board.signature(), move.value, this.depth);
                }
            }
            

            // DO PARALLEL STUFF
            SplitTask<M, B> remaining = new SplitTask<M, B>(eval, board, depth, moves, null,
                    (int)(PERCENTAGE_SEQUENTIAL * moves.size()), moves.size(),
                    cutoff, divideCutoff, alpha, beta);
            
            BestMove<M> remainingRes = remaining.compute();
            
            if (best.value >= remainingRes.value) {
                return best;
            } else {
                return remainingRes;
            }
        }
        
        private void sortMoves() {
            List<M> sortedMoves = new ArrayList<M>();
            for (int i = 0; i < this.moves.size(); i++) {
                M curmove = this.moves.get(i);
                if (curmove.isPromotion() || curmove.isCapture()) {
                    sortedMoves.add(curmove);
                    this.moves.remove(i);
                    i--;
                }
            }
            sortedMoves.addAll(this.moves);
            this.moves = sortedMoves;
            
            /*
             * This one sometimes crashes because comparator violates contract
            this.moves.sort(new Comparator<M>() {
                @Override
                public int compare(M x, M y) {
                    if (x.isPromotion()) {
                        if (y.isPromotion()) {
                            return 0;
                        } else {
                            return -1;
                        }
                    } else if (x.isCapture()) {
                        if (y.isPromotion()) {
                            return 1;
                        } else if (y.isCapture()) {
                            return 0;
                        } else {
                            return -1;
                        }
                    } else {
                        if (y.isCapture() && y.isPromotion()) {
                            return 1;
                        } else {
                            return 0;
                        }
                    }
                }
            });
            */
        }
    }
    
    private static class SplitTask<M extends Move<M>, B extends Board<M, B>> extends RecursiveTask<BestMove<M>> {
        
        Evaluator<B> eval;
        B board;
        int depth;
        List<M> moves;
        M move;
        int start, end;
        int cutoff, divideCutoff;
        int alpha, beta;
        
        public SplitTask(Evaluator<B> eval, B board, int depth, List<M> moves, M move,
                int start, int end, int cutoff, int divideCutoff, int alpha, int beta) {
            this.eval = eval;
            this.board = board;
            this.depth = depth;
            this.moves = moves;
            this.move = move;
            this.start = start;
            this.end = end;
            this.cutoff = cutoff;
            this.divideCutoff = divideCutoff;
            this.alpha = alpha;
            this.beta = beta;
        }

//        Case 1: depth <= cutoff: call sequential minimax
//        Case 2: region <= divideCutoff: fork sequentially
//          Copy board if need to apply new move.
//          send next move, decrement depth, reset start/end according to next move
//        Case 3: region > divideCutoff: split in half and fork
//          change start/end
        @Override
        protected BestMove<M> compute() {    

            if (move != null) {
                // move should be null unless we hit case 2 and are recursing
                //  --> copy board, apply move, get new set of moves, and reset start/end
                this.board = board.copy();
                this.board.applyMove(move);
                this.moves = board.generateMoves();
                this.start = 0;
                this.end = this.moves.size();
            }
            
            // CASE 1 covered by AlphaBetaTask
            
            // CASE 2
            if (end - start <= divideCutoff) {
                
                List<AlphaBetaTask<M, B>> forks = new ArrayList<AlphaBetaTask<M, B>>();
                for (int i = start+1; i < end; i++) {
                    AlphaBetaTask<M, B> curFork = new AlphaBetaTask<M, B>(eval, board, depth-1,
                            null, moves.get(i), 0, 0, cutoff, divideCutoff, -beta, -alpha);
                        // passing null for moves, and 0 for start/end is fine
                        //  because move != null, and child will deal with it
                    curFork.fork();
                    forks.add(curFork);
                }
                
                AlphaBetaTask<M, B> lastFork = new AlphaBetaTask<M, B>(eval, board, depth-1,
                        null, moves.get(start), 0, 0, cutoff, divideCutoff, -beta, -alpha);
                
                List<BestMove<M>> results = new ArrayList<BestMove<M>>();
                results.add(lastFork.compute());
                for (int i = 0; i < forks.size(); i++) {
                    AlphaBetaTask<M, B> fork = forks.get(i);
                    results.add(fork.join());
                }

                BestMove<M> best = new BestMove<M>(alpha);
                for (int i = 0; i < results.size(); i++) {
                    BestMove<M> cur = results.get(i);
                    M curMove = moves.get(start + i);
                    cur.negate();
                    if (cur.value > alpha) {
                        alpha = cur.value;
                        best = cur;
                        best.move = curMove;
                    }
                }
                return best;
            }
            
            // CASE 3
            int mid = start + (end - start)/2;
            SplitTask<M, B> left = new SplitTask<M, B>(eval, board, depth, moves,
                    null, start, mid, cutoff, divideCutoff, alpha, beta);
            SplitTask<M, B> right = new SplitTask<M, B>(eval, board, depth, moves,
                    null, mid, end, cutoff, divideCutoff, alpha, beta);
            
            right.fork();
            
            BestMove<M> leftRes = left.compute();
            BestMove<M> rightRes = right.join();
            if (leftRes.value >= rightRes.value) {
                return leftRes;
            } else {
                return rightRes;
            }
        }
    }
}









