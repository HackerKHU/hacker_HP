import './App.css'

function App() {
  return (
    <main className="app-shell">
      <section className="intro" aria-labelledby="page-title">
        <p className="eyebrow">HACKER KHU</p>
        <h1 id="page-title">동아리 홈페이지를 준비하고 있어요.</h1>
        <p className="description">
          회원 승인과 공지 기능을 먼저 제공할 예정입니다.
        </p>
        <p className="status" role="status">
          <span className="status-dot" aria-hidden="true" />
          서비스 준비 중
        </p>
      </section>
    </main>
  )
}

export default App
