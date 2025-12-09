import React, { useEffect, useRef } from 'react';
import './LandingPage.css';
// Remova esta linha:
// import dashboardImage from '../../assets/dashboard-placeholder.png';
import ThemeToggleButton from '../../ThemeToggleButton';
import {API_BASE_URL} from '../../../apiConfig';

    
const Header = () => {
    const headerRef = useRef(null);

    useEffect(() => {
        if (window.gsap && headerRef.current) {
            window.gsap.from(headerRef.current, {
                y: -100,
                opacity: 0,
                duration: 0.8,
                ease: 'power3.out'
            });
        }
    }, []);

    return (
        <header className="landing-header" ref={headerRef}>
            <div className="landing-container">
                <a href="/" className="logo">CarteiraPro</a>
                <nav className="main-nav">
                    <a href="#contato">Contato</a>
                </nav>
                <div className="flex items-center gap-4">
                    <ThemeToggleButton />
                    <a href={`${API_BASE_URL}/oauth2/authorization/google`} className="cta-button google-login-button">
                        <svg width="18" height="18" viewBox="0 0 18 18" fill="none" xmlns="http://www.w3.org/2000/svg">
                            <path d="M17.64 9.2c0-.637-.057-1.251-.164-1.84H9v3.481h4.844c-.209 1.125-.843 2.078-1.796 2.717v2.258h2.908c1.702-1.567 2.684-3.874 2.684-6.615z" fill="#4285F4"/>
                            <path d="M9.003 18c2.43 0 4.467-.806 5.956-2.18l-2.909-2.259c-.806.54-1.836.86-3.047.86-2.344 0-4.328-1.584-5.036-3.711H.957v2.332C2.438 15.983 5.482 18 9.003 18z" fill="#34A853"/>
                            <path d="M3.964 10.71c-.18-.54-.282-1.117-.282-1.71s.102-1.17.282-1.71V4.958H.957C.347 6.173 0 7.548 0 9s.348 2.827.957 4.042l3.007-2.332z" fill="#FBBC05"/>
                            <path d="M9.003 3.58c1.321 0 2.508.454 3.44 1.345l2.582-2.58C13.464.891 11.426 0 9.003 0 5.482 0 2.438 2.017.957 4.958L3.964 7.29c.708-2.127 2.692-3.71 5.039-3.71z" fill="#EA4335"/>
                        </svg>
                        <span>Faça login para acessar</span>
                    </a>
                </div>
            </div>
        </header>
    );
};

const Hero = ({ dashboardImage }) => {
    const heroRef = useRef(null);
    const titleRef = useRef(null);
    const subtitleRef = useRef(null);
    const buttonsRef = useRef(null);
    const imageRef = useRef(null);

    useEffect(() => {
        if (window.gsap && heroRef.current) {
            const tl = window.gsap.timeline({ defaults: { ease: 'power3.out' } });

            tl.from(titleRef.current, {
                y: 50,
                opacity: 0,
                duration: 0.8
            })
            .from(subtitleRef.current, {
                y: 30,
                opacity: 0,
                duration: 0.6
            }, '-=0.4')
            .from(buttonsRef.current.children, {
                y: 20,
                opacity: 0,
                duration: 0.5,
                stagger: 0.1
            }, '-=0.3')
            .from(imageRef.current, {
                y: 40,
                opacity: 0,
                scale: 0.95,
                duration: 0.8
            }, '-=0.4');

            if (window.ScrollTrigger) {
                window.gsap.to(imageRef.current, {
                    y: 50,
                    scrollTrigger: {
                        trigger: heroRef.current,
                        start: 'top top',
                        end: 'bottom top',
                        scrub: 1
                    }
                });
            }
        }
    }, []);

    return (
        <section className="hero-section" ref={heroRef}>
            <div className="landing-container hero-grid-container">
                <div className="hero-left-column">
                    <h1 className="hero-title" ref={titleRef}>
                        Controle total<br/>dos seus<br/>
                        <span className="gradient-text">investimentos.</span>
                    </h1>
                </div>

                <div className="hero-right-column">
                    <p className="hero-subtitle" ref={subtitleRef}>
                        Visualize sua carteira, acompanhe a rentabilidade e tome decisões mais inteligentes. De Ações a Cripto, tudo em um só lugar.
                    </p>
                    <div className="hero-buttons" ref={buttonsRef}>
                        <a href="#features" className="cta-button secondary-cta large-cta">
                            Saiba Mais
                        </a>
                    </div>
                    <div className="hero-image-wrapper" ref={imageRef}>
                        <img src={dashboardImage} alt="Dashboard da aplicação CarteiraPro" className="hero-image" />
                    </div>
                </div>
            </div>
        </section>
    );
};

const Features = () => {
    const featuresRef = useRef(null);
    const cardsRef = useRef([]);

    const featuresData = [
        { title: 'Portfólio Unificado', description: 'Adicione Ações (B3/EUA), ETFs, Criptomoedas e Renda Fixa para uma visão completa.' },
        { title: 'Visualização Inteligente', description: 'Gráficos interativos mostram sua alocação, ajudando a identificar riscos e oportunidades.' },
        { title: 'Acompanhamento Preciso', description: 'Atualize as cotações e veja a rentabilidade de cada ativo e da sua carteira consolidada.' }
    ];

    useEffect(() => {
        if (window.gsap && window.ScrollTrigger && featuresRef.current) {
            cardsRef.current.forEach((card, index) => {
                if (card) {
                    window.gsap.from(card, {
                        y: 60,
                        opacity: 0,
                        duration: 0.8,
                        ease: 'power3.out',
                        scrollTrigger: {
                            trigger: card,
                            start: 'top 80%',
                            end: 'top 50%',
                            toggleActions: 'play none none reverse'
                        }
                    });

                    card.addEventListener('mouseenter', () => {
                        window.gsap.to(card, {
                            y: -10,
                            scale: 1.03,
                            duration: 0.3,
                            ease: 'power2.out'
                        });
                    });

                    card.addEventListener('mouseleave', () => {
                        window.gsap.to(card, {
                            y: 0,
                            scale: 1,
                            duration: 0.3,
                            ease: 'power2.out'
                        });
                    });
                }
            });
        }
    }, []);

    return (
        <section id="features" className="features-section" ref={featuresRef}>
            <div className="landing-container text-center">
                <h2 className="section-title">Uma plataforma completa para o investidor</h2>
                <p className="section-subtitle">
                    Tudo o que você precisa para gerenciar seus investimentos com confiança.
                </p>
                <div className="features-grid">
                    {featuresData.map((feature, index) => (
                        <div
                            key={index}
                            ref={el => cardsRef.current[index] = el}
                            className="feature-card"
                        >
                            <h3 className="feature-title">{feature.title}</h3>
                            <p className="feature-description">{feature.description}</p>
                        </div>
                    ))}
                </div>
            </div>
        </section>
    );
};

const Footer = () => (
    <footer className="landing-footer">
        <div className="landing-container">
            <p>&copy; {new Date().getFullYear()} Matheus Dias Estacio. Todos os direitos reservados.</p>
        </div>
    </footer>
);

const LandingPage = ({ dashboardImage }) => {
    useEffect(() => {
        document.querySelectorAll('a[href^="#"]').forEach(anchor => {
            anchor.addEventListener('click', function (e) {
                const href = this.getAttribute('href');
                if (href === '#') return;
                
                e.preventDefault();
                const target = document.querySelector(href);
                if (target) {
                    target.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
            });
        });
    }, []);

    return (
        <div className="landing-page">
            <Header />
            <main>
                <Hero dashboardImage={dashboardImage} />
                <Features />
            </main>
            <Footer />
        </div>
    );
};

export default LandingPage;