package cn.listenly.app.ui.practice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cn.listenly.app.AppContainer
import cn.listenly.app.data.AnswerValue
import cn.listenly.app.model.Article
import cn.listenly.app.model.Paper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Answers for one paper, keyed `articleId/questionId`. */
class PracticeViewModel(private val c: AppContainer, val paperId: String) : ViewModel() {

    val answers: StateFlow<Map<String, AnswerValue>> =
        c.study.observePaperAnswers(paperId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun choose(article: Article, questionId: String, option: String) = viewModelScope.launch {
        c.study.choose(paperId, article.id, questionId, option)
    }

    /** Returns via [onDone] whether the article was submitted (false if a question is unanswered). */
    fun submit(paper: Paper, article: Article, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        onDone(c.study.submitArticle(paper, article))
    }

    fun reset(article: Article) = viewModelScope.launch { c.study.resetArticle(paperId, article) }

    fun resetPaper(paper: Paper) = viewModelScope.launch { paper.articles.forEach { c.study.resetArticle(paperId, it) } }
}

/** Score for a paper from stored answers; unanswerable (no key) questions are excluded. */
data class PaperScore(val correct: Int, val scored: Int, val answered: Int, val total: Int)

fun score(paper: Paper, answers: Map<String, AnswerValue>): PaperScore {
    var correct = 0
    var scored = 0
    var answered = 0
    var total = 0
    for (article in paper.articles) for (q in article.questions) {
        total++
        val a = answers["${article.id}/${q.id}"]?.takeIf { it.submitted } ?: continue
        answered++
        if (q.answer != null) {
            scored++
            if (a.choice == q.answer) correct++
        }
    }
    return PaperScore(correct, scored, answered, total)
}
