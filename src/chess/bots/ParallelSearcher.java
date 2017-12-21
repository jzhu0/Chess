package chess.bots;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;

import cse332.chess.interfaces.AbstractSearcher;
import cse332.chess.interfaces.Board;
import cse332.chess.interfaces.Evaluator;
import cse332.chess.interfaces.Move;


public class ParallelSearcher<M extends Move<M>, B extends Board<M, B>> extends
        AbstractSearcher<M, B> {
    
    private static final ForkJoinPool POOL = new ForkJoinPool();
    // protected int ply
    // protected int cutoff
    protected int divideCutoff = 4;
    
    // Similar to SimpleSearcher
    public M getBestMove(B board, int myTime, int opTime) {
        BestMove<M> best = parMinimax(this.evaluator, board, this.ply, this.cutoff, this.divideCutoff);
        
        reportNewBestMove(best.move);
        return best.move;
    }
    
    // Sort of imitates SimpleSearcher?
    // if current depth < cutoff, then do sequentially (call sequential minimax)
    // else:
    // if moves is empty, deal with it. --> not sure if this is actually needed
    // otherwise: create a SplitTask -> see SplitTask comments
    static <M extends Move<M>, B extends Board<M, B>> BestMove<M> parMinimax
            (Evaluator<B> evaluator, B board, int depth, int cutoff, int divideCutoff) {
        
        List<M> moves = board.generateMoves();
        BestMove<M> best = POOL.invoke(new SplitTask<M, B>(evaluator, board, depth,
                moves, null, 0, moves.size(), cutoff, divideCutoff));
        return best;
    }
    
    // RecursiveTask that returns a BestMove<M>.
    // Splits up the List<Move> using divide and conquer.
    // Once the partition is smaller than divideCutoff, try each move sequentially by calling sequentialTry()
    private static class SplitTask<M extends Move<M>, B extends Board<M, B>> extends RecursiveTask<BestMove<M>> {
        
        Evaluator<B> eval;
        B board;
        int depth;
        List<M> moves;
        M move;
        int start, end;
        int cutoff, divideCutoff;
        
        public SplitTask(Evaluator<B> eval, B board, int depth, List<M> moves, M move,
                int start, int end, int cutoff, int divideCutoff) {
            this.eval = eval;
            this.board = board;
            this.depth = depth;
            this.moves = moves;
            this.move = move;
            this.start = start;
            this.end = end;
            this.cutoff = cutoff;
            this.divideCutoff = divideCutoff;
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
            if (this.moves.isEmpty()) {
                if (this.board.inCheck()) {
                    return new BestMove<M>(0 - eval.mate() - depth);
                } else {
                    return new BestMove<M>(0 - eval.stalemate());
                }
            }
            
            // CASE 1
            if (depth <= cutoff) {
                // do sequentially using SimpleSearcher
                return SimpleSearcher.minimax(eval, board, depth);
            }
            
            // CASE 2
            if (end - start <= divideCutoff) {
                
                List<SplitTask<M, B>> forks = new ArrayList<SplitTask<M, B>>();
                for (int i = start+1; i < end; i++) {
                    SplitTask<M, B> curFork = new SplitTask<M, B>(eval, board, depth-1,
                            null, moves.get(i), 0, 0, cutoff, divideCutoff);
                        // passing null for moves, and 0 for start/end is fine
                        //  because move != null, and child will deal with it
                    curFork.fork();
                    forks.add(curFork);
                }
                
                SplitTask<M, B> lastFork = new SplitTask<M, B>(eval, board, depth-1,
                        null, moves.get(start), 0, 0, cutoff, divideCutoff);
                
                List<BestMove<M>> results = new ArrayList<BestMove<M>>();
                results.add(lastFork.compute());
                for (int i = 0; i < forks.size(); i++) {
                    SplitTask<M, B> fork = forks.get(i);
                    results.add(fork.join());
                }
                
                BestMove<M> best = new BestMove<M>(0 - eval.infty());
                for (int i = 0; i < results.size(); i++) {
                    BestMove<M> cur = results.get(i);
                    M curMove = moves.get(start + i);
                    cur.negate();
                    if (cur.value > best.value) {
                        best = cur;
                        best.move = curMove;
                    }
                }
                return best;
            }
            
            // CASE 3
            int mid = start + (end - start)/2;
            SplitTask<M, B> left = new SplitTask<M, B>(eval, board, depth, moves,
                    null, start, mid, cutoff, divideCutoff);
            SplitTask<M, B> right = new SplitTask<M, B>(eval, board, depth, moves,
                    null, mid, end, cutoff, divideCutoff);
            
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

/**
 * -------------------------- Checkpoint 1 comments! ------------------------- *
 *
 * The stuff that we check in parMinimax() at the moment should be in compute()
 * because we want to be performing these tasks every time *after* we call
 * POOL.invoke() rather than going back to parMinimax(), performing those
 * checks, and then having to call POOL.invoke() again and again.
 *
 * sequentialTry() should be unnecessary, but she was a bit unsure of what it
 * was doing so maybe not.
 *
 * The purpose of divideCutoff is to still do parallelism but quit doing divide
 * and conquer once we reach that cutoff, and instead fork the rest of the
 * threads sequentially. It'd be forking the rest of the threads and computing
 * the current thread.
 */




